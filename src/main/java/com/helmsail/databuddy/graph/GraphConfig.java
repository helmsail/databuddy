package com.helmsail.databuddy.graph;

import java.util.Map;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;

/**
 * 图装配(骨架版):拓扑、状态键策略、检查点——"怎么把图拼出来"都在这里;
 * 执行编排(跑图/事件/运行表/停止/释放)在 GraphService;当前拓扑:入口 → 意图识别 → 终点
 */
@Configuration
public class GraphConfig {

	/** 检查点:MySQL 存档(系统库);"跑完即释放"的时机由 GraphService 编排 */
	@Bean
	public BaseCheckpointSaver checkpointSaver(DataSource dataSource) {
		return MysqlSaver.builder().dataSource(dataSource).build();
	}

	@Bean
	public CompiledGraph databuddyGraph(BaseCheckpointSaver checkpointSaver, IntentRecognitionNode intentRecognitionNode)
			throws GraphStateException {
		KeyStrategyFactory keyStrategyFactory = () -> Map.of(
				GraphKeys.INPUT, KeyStrategy.REPLACE,
				GraphKeys.AGENT_ID, KeyStrategy.REPLACE,
				GraphKeys.HISTORY, KeyStrategy.REPLACE,
				GraphKeys.FINAL_ANSWER, KeyStrategy.REPLACE,
				GraphKeys.CLASSIFICATION, KeyStrategy.REPLACE);
		return new StateGraph("databuddy", keyStrategyFactory)
			// 拓扑:入口 → 意图识别 → 按分类分流(chat 与 data_analysis 当前都到终点;数据链接入后,后者改指其首节点)
			.addNode(GraphKeys.INTENT_RECOGNITION, intentRecognitionNode)
			.addEdge(START, GraphKeys.INTENT_RECOGNITION)
			.addConditionalEdges(GraphKeys.INTENT_RECOGNITION,
					AsyncEdgeAction.edge_async(
							state -> state.value(GraphKeys.CLASSIFICATION, String.class).orElse(GraphKeys.INTENT_CHAT)),
					Map.of(GraphKeys.INTENT_CHAT, END, GraphKeys.INTENT_DATA_ANALYSIS, END))
			.compile(CompileConfig.builder()
				.saverConfig(SaverConfig.builder().register(checkpointSaver).build())
				.build());
	}

}
