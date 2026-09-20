package com.helmsail.databuddy.graph.python;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.micrometer.observation.annotation.Observed;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.helmsail.databuddy.graph.GraphKeys;
import com.helmsail.databuddy.graph.plan.PlanUtils;
import com.helmsail.databuddy.graph.util.NodeUtils;
import com.helmsail.databuddy.python.PythonSandboxFactory;
import com.helmsail.databuddy.python.SandboxResult;

import lombok.extern.slf4j.Slf4j;

/**
 * Python 执行节点:把生成代码与数据素材(input.json = 最近一次 SQL 结果契约 JSON)交给沙箱运行。
 * 成功(stdout JSON 或 /work/output 产物):stdout 写 PYTHON_RESULT 与 STEP_RESULTS[step_N],转分析;
 * 失败(代码错/超时/无产出):原因写 PYTHON_FAIL_REASON 打回生成重写;尝试超限走升级阶梯:
 * 全局重规划 ≤ MAX_PLAN_REPAIR 次,再超限终止语收场。
 * 阻塞的沙箱调用发生在图订阅线程(boundedElastic)上,不占事件循环
 */
@Slf4j
@Component
public class PythonExecuteNode implements AsyncNodeAction {

	/** Python 组尝试上限(执行失败重生成计数;超限升级重规划) */
	private static final int MAX_PYTHON_ATTEMPT = 3;

	/** 升级超限终止语(用户可见) */
	private static final String TERMINATION = "Python 多次执行失败,本轮分析无法完成。建议换个角度描述问题后重试。";

	private final PythonSandboxFactory sandboxFactory;

	public PythonExecuteNode(PythonSandboxFactory sandboxFactory) {
		this.sandboxFactory = sandboxFactory;
	}

	@Override
	@Observed(name = "node.pythonExecute", contextualName = "Python 执行")
	public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
		String code = state.value(GraphKeys.PYTHON_CODE, String.class).orElse("");
		if (!StringUtils.hasText(code)) {
			return CompletableFuture.completedFuture(fail(state, "生成结果为空,没有可执行的代码"));
		}
		String inputJson = state.value(GraphKeys.SQL_RESULT, String.class).orElse("{}");
		SandboxResult result;
		try {
			result = sandboxFactory.execute(code, inputJson);
		}
		catch (RuntimeException e) {
			return CompletableFuture.completedFuture(fail(state, "沙箱不可用: " + e.getMessage()));
		}
		boolean success = result.failure() == null && result.exitCode() == 0 && result.hasOutput();
		if (!success) {
			return CompletableFuture.completedFuture(fail(state, failureReason(result)));
		}
		int step = state.value(GraphKeys.PLAN_STEP, 1);
		String stdout = result.stdout() == null ? "" : result.stdout();
		Map<String, String> results = PlanUtils.withEntry(stepResults(state), "step_" + step, stdout);
		String files = filesText(result);
		log.info("Python 执行成功: 第 {} 步, stdout {} 字符, 产物: {}", step, stdout.length(), files);
		return CompletableFuture.completedFuture(Map.of(GraphKeys.PYTHON_FAILED, false, GraphKeys.PYTHON_RESULT, stdout,
				GraphKeys.PYTHON_FILES, files, GraphKeys.PYTHON_FAIL_REASON, "", GraphKeys.PYTHON_NEXT, "analyze",
				GraphKeys.STEP_RESULTS, results, GraphKeys.NODE_STATUS, "Python 执行完成:" + filesNote(result)));
	}

	/** 失败:未超限打回生成(带原因);超限升级重规划,再超限终止语收场 */
	private Map<String, Object> fail(OverAllState state, String reason) {
		int attempt = state.value(GraphKeys.PYTHON_ATTEMPT, 0);
		log.warn("Python 执行失败(第 {} 次尝试): {}", attempt, reason);
		if (attempt >= MAX_PYTHON_ATTEMPT) {
			int count = state.value(GraphKeys.PLAN_REPAIR_COUNT, 0) + 1;
			if (count > PlanUtils.MAX_PLAN_REPAIR) {
				return Map.of(GraphKeys.PYTHON_FAILED, true, GraphKeys.PYTHON_FAIL_REASON, reason, GraphKeys.PYTHON_NEXT,
						"end", GraphKeys.FINAL_ANSWER, TERMINATION, GraphKeys.NODE_STATUS, "Python 组重试超限且重规划超限:终止");
			}
			return Map.of(GraphKeys.PYTHON_FAILED, true, GraphKeys.PYTHON_FAIL_REASON, reason, GraphKeys.PYTHON_NEXT,
					"replan", GraphKeys.PLAN_REPAIR_COUNT, count, GraphKeys.PLAN_REPAIR_REASON, "Python 组多次失败: " + reason,
					GraphKeys.PLAN_STEP, 1, GraphKeys.PYTHON_ATTEMPT, 0, GraphKeys.NODE_STATUS, "Python 组重试超限:升级重规划");
		}
		return Map.of(GraphKeys.PYTHON_FAILED, true, GraphKeys.PYTHON_FAIL_REASON, reason, GraphKeys.PYTHON_NEXT,
				"regenerate", GraphKeys.NODE_STATUS, "Python 执行失败,重新生成");
	}

	/** 失败原因:分类(stderr 截断)——写进重写提示词供"带原文改" */
	private String failureReason(SandboxResult result) {
		String stderr = result.stderr() == null ? "" : result.stderr();
		if (result.failure() != null) {
			return "失败类型: " + result.failure() + ";错误输出: " + NodeUtils.brief(stderr);
		}
		if (result.exitCode() != 0) {
			return "退出码 " + result.exitCode() + ";错误输出: " + NodeUtils.brief(stderr);
		}
		return "无产出: 代码运行成功但没有输出(需要 stdout JSON 或 /work/output 产物)";
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

	/** 播报里的产物部分 */
	private String filesNote(SandboxResult result) {
		return result.files().isEmpty() ? "无产物" : "产物 " + result.files().size() + " 个";
	}

	/** 分步结果累积(整表回写:REPLACE 键语义) */
	private Map<String, String> stepResults(OverAllState state) {
		Object raw = state.value(GraphKeys.STEP_RESULTS).orElse(null);
		if (raw instanceof Map<?, ?> map) {
			Map<String, String> results = new HashMap<>();
			map.forEach((key, value) -> results.put(String.valueOf(key), value == null ? "" : String.valueOf(value)));
			return results;
		}
		return Map.of();
	}

}
