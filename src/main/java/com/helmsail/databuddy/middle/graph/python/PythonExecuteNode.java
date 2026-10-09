package com.helmsail.databuddy.middle.graph.python;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.micrometer.observation.annotation.Observed;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helmsail.databuddy.middle.graph.GraphKeys;
import com.helmsail.databuddy.middle.graph.plan.PlanUtils;
import com.helmsail.databuddy.middle.graph.util.NodeUtils;
import com.helmsail.databuddy.middle.python.PythonSandboxService;
import com.helmsail.databuddy.middle.python.SandboxResult;

import lombok.extern.slf4j.Slf4j;

/**
 * Python 执行节点:把生成代码与数据素材(input.json = 最近一次 SQL 结果契约 JSON)交给沙箱运行。
 * 成功(stdout JSON 或 /work/output 产物):stdout 写 PYTHON_RESULT 与 STEP_RESULTS[step_N](产物清单随其后,报告可见),
 * 图片产物转 base64 写 PYTHON_IMAGES(推流层发 image 帧直显),转分析;
 * 失败(代码错/超时/无产出):原因写 PYTHON_REPAIR_REASON 打回生成重写;超限与否由生成口统一裁决(组内 ≤ PYTHON_RETRY_MAX → 升级重规划)。
 * 阻塞的沙箱调用发生在图订阅线程(boundedElastic)上,不占事件循环
 */
@Slf4j
@Component
public class PythonExecuteNode implements AsyncNodeAction {

	private final PythonSandboxService sandboxService;

	private final ObjectMapper objectMapper;

	public PythonExecuteNode(PythonSandboxService sandboxService, ObjectMapper objectMapper) {
		this.sandboxService = sandboxService;
		this.objectMapper = objectMapper;
	}

	@Override
	@Observed(name = "node.pythonExecute", contextualName = "Python 执行")
	public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
		String code = state.value(GraphKeys.Info.PYTHON_CODE, String.class).orElse("");
		if (!StringUtils.hasText(code)) {
			return CompletableFuture.completedFuture(fail(state, "生成结果为空,没有可执行的代码"));
		}
		String inputJson = state.value(GraphKeys.Info.SQL_RESULT, String.class).orElse("{}");
		SandboxResult result;
		try {
			result = sandboxService.execute(code, inputJson);
		}
		catch (RuntimeException e) {
			return CompletableFuture.completedFuture(fail(state, "沙箱不可用: " + e.getMessage()));
		}
		boolean success = result.type() == SandboxResult.Type.SUCCESS && result.hasOutput();
		if (!success) {
			return CompletableFuture.completedFuture(fail(state, failureReason(result)));
		}
		int step = NodeUtils.intOf(state, GraphKeys.Control.PLAN_STEP_NO, 1);
		String stdout = result.stdout() == null ? "" : result.stdout();
		String files = filesText(result);
		Map<String, String> results = PlanUtils.withEntry(stepResults(state), "step_" + step, withFiles(stdout, files));
		log.info("Python 执行成功: 第 {} 步, stdout {} 字符, 产物: {}", step, stdout.length(), files);
		return CompletableFuture.completedFuture(Map.of(GraphKeys.Info.PYTHON_RESULT, stdout, GraphKeys.Control.PYTHON_REPAIR_REASON, "",
				GraphKeys.Control.PYTHON_NEXT, "analyze", GraphKeys.Info.STEP_RESULTS, results, GraphKeys.Info.PYTHON_IMAGES,
				imagesJson(result), GraphKeys.Info.PROGRESS, "Python 执行完成:" + filesNote(result)));
	}

	/** 失败:原因写 PYTHON_REPAIR_REASON 打回生成(超限与否由生成口统一裁决) */
	private Map<String, Object> fail(OverAllState state, String reason) {
		int attempt = NodeUtils.intOf(state, GraphKeys.Control.PYTHON_RETRY_COUNT, 0);
		log.warn("Python 执行失败(第 {} 次尝试): {}", attempt, reason);
		return Map.of(GraphKeys.Control.PYTHON_REPAIR_REASON, reason, GraphKeys.Control.PYTHON_NEXT, "regenerate", GraphKeys.Info.PROGRESS,
				"Python 执行失败,重新生成");
	}

	/** 失败原因:类型 + stderr(截断)——写进重写提示词供"带原文改";类型为成功但无产出时单独说明 */
	private String failureReason(SandboxResult result) {
		String stderr = result.stderr() == null ? "" : result.stderr();
		if (result.type() != SandboxResult.Type.SUCCESS) {
			return "失败类型: " + result.type() + ";错误输出: " + NodeUtils.brief(stderr);
		}
		return "无产出: 代码运行成功但没有输出(需要 stdout JSON 或 /work/output 产物)";
	}

	/** 图片产物转 base64 JSON 数组(name/mime/data);无图或序列化失败为"",不阻塞成功路径(清单已在 STEP_RESULTS) */
	private String imagesJson(SandboxResult result) {
		List<Map<String, String>> images = new ArrayList<>();
		for (SandboxResult.OutputFile file : result.files()) {
			String mime = mimeOf(file.name());
			if (mime == null) {
				continue;
			}
			images.add(Map.of("name", file.name(), "mime", mime, "data", Base64.getEncoder().encodeToString(file.content())));
		}
		if (images.isEmpty()) {
			return "";
		}
		try {
			return objectMapper.writeValueAsString(images);
		}
		catch (Exception e) {
			log.warn("图片产物序列化失败,跳过直推: {}", e.getMessage());
			return "";
		}
	}

	/** 按文件名判定图片类型(非图片产物不进直推,仅保留清单) */
	private static String mimeOf(String name) {
		String lower = name.toLowerCase();
		if (lower.endsWith(".png")) {
			return "image/png";
		}
		if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
			return "image/jpeg";
		}
		if (lower.endsWith(".gif")) {
			return "image/gif";
		}
		if (lower.endsWith(".webp")) {
			return "image/webp";
		}
		if (lower.endsWith(".svg")) {
			return "image/svg+xml";
		}
		return null;
	}

	/** 产物清单文本:文件名(字节数);无产物为"无" */
	private String filesText(SandboxResult result) {
		if (result.files().isEmpty()) {
			return "无";
		}
		StringBuilder text = new StringBuilder();
		for (SandboxResult.OutputFile file : result.files()) {
			if (!text.isEmpty()) {
				text.append(", ");
			}
			text.append(file.name()).append(" (").append(file.content().length).append(" B)");
		}
		return text.toString();
	}

	/** 步结果文本:stdout 附产出文件清单(无产物为原文;随 STEP_RESULTS 进报告) */
	private String withFiles(String stdout, String files) {
		return "无".equals(files) ? stdout : stdout + "\n[产出文件] " + files;
	}

	/** 播报里的产物部分 */
	private String filesNote(SandboxResult result) {
		return result.files().isEmpty() ? "无产物" : "产物 " + result.files().size() + " 个";
	}

	/** 分步结果累积(整表回写:REPLACE 键语义) */
	private Map<String, String> stepResults(OverAllState state) {
		Object raw = state.value(GraphKeys.Info.STEP_RESULTS).orElse(null);
		if (raw instanceof Map<?, ?> map) {
			Map<String, String> results = new HashMap<>();
			map.forEach((key, value) -> results.put(String.valueOf(key), value == null ? "" : String.valueOf(value)));
			return results;
		}
		return Map.of();
	}

}
