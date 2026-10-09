package com.helmsail.databuddy.middle.graph.plan;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.micrometer.observation.annotation.Observed;

import org.springframework.stereotype.Component;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helmsail.databuddy.middle.graph.GraphKeys;
import com.helmsail.databuddy.middle.graph.python.PythonConstants;
import com.helmsail.databuddy.middle.graph.report.ReportConstants;
import com.helmsail.databuddy.middle.graph.review.ReviewConstants;
import com.helmsail.databuddy.middle.graph.util.NodeUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * 计划执行节点(枢纽):零 LLM 的调度器。每次到达做两件事:
 * ①人工确认闸(开关开启且未确认 → 转确认节点,确认节点会把开关关掉,只拦一次);
 * ②按当前步派活(SQL / Python 生成),步数走完转报告生成固定收尾;
 * 轻档下遇 python 步不执行、直接收束(计划侧已约束只排 SQL 步,这里是硬校验)。
 * 计划的结构校验在规划节点(生成侧)完成,过厂的计划这里直接信任;
 * 下一跳写 PLAN_NEXT_NODE,由分流器读;步数推进不在这里(执行成功后由组内节点 +1)
 */
@Slf4j
@Component
public class PlanExecutorNode implements AsyncNodeAction {

	private final ObjectMapper objectMapper;

	public PlanExecutorNode(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	@Observed(name = "node.planExecutor", contextualName = "计划执行")
	public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
		// 人工确认闸:开启则先转确认节点(确认后开关被关掉,后续步不再拦)——闸在计划读取之前
		if (Boolean.TRUE.equals(state.value(GraphKeys.Control.HUMAN_REVIEW_ENABLED, false))) {
			return CompletableFuture.completedFuture(Map.of(GraphKeys.Control.PLAN_NEXT_NODE, ReviewConstants.PLAN_REVIEW, GraphKeys.Info.PROGRESS,
					"计划待确认:请确认后继续执行"));
		}
		String planJson = state.value(GraphKeys.Info.PLAN_JSON, String.class).orElse("");
		int step = NodeUtils.intOf(state, GraphKeys.Control.PLAN_STEP_NO, 1);
		Plan plan = PlanUtils.parse(objectMapper, planJson);
		int size = plan.getPlanSteps().size();
		boolean light = Boolean.TRUE.equals(state.value(GraphKeys.Control.NL2SQL_ENABLED, false));
		// 步数走完:轻档直接到终点(跳过报告,SQL 文本即结果);常规走报告固定收尾(计划里没有报告步)
		if (step > size) {
			if (light) {
				log.info("轻档模式:计划执行完成(共 {} 步),直接收束", size);
				return CompletableFuture.completedFuture(Map.of(GraphKeys.Control.PLAN_NEXT_NODE, StateGraph.END, GraphKeys.Info.PROGRESS,
						"轻档完成:SQL 已生成并执行"));
			}
			log.info("计划执行完成: 共 {} 步,转报告生成", size);
			return CompletableFuture.completedFuture(Map.of(GraphKeys.Control.PLAN_NEXT_NODE, ReportConstants.REPORT_GENERATOR,
					GraphKeys.Info.PROGRESS, "计划执行完成:共 " + size + " 步,开始生成报告"));
		}
		PlanStep current = PlanUtils.stepAt(plan, step);
		// 轻档校验:计划侧应只排 SQL 步;模型顶风排了 python 步则不执行,直接收束(保险丝,不丢已得结果)
		if (light && PythonConstants.PYTHON_GENERATE.equals(current.getSelectGroup())) {
			log.info("轻档模式:第 {} 步为 Python 步,按约定不执行,直接收束", step);
			return CompletableFuture.completedFuture(Map.of(GraphKeys.Control.PLAN_NEXT_NODE, StateGraph.END, GraphKeys.Info.PROGRESS,
					"轻档完成:SQL 已生成并执行"));
		}
		log.info("派活: 第 {}/{} 步 → {}", step, size, current.getSelectGroup());
		String groupText = PythonConstants.PYTHON_GENERATE.equals(current.getSelectGroup()) ? "Python 生成" : "SQL 生成";
		return CompletableFuture.completedFuture(Map.of(GraphKeys.Control.PLAN_NEXT_NODE, current.getSelectGroup(), GraphKeys.Info.PROGRESS,
				"计划执行:第 " + step + "/" + size + " 步(" + groupText + ")"));
	}

}
