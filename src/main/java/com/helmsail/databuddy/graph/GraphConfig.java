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
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;

/**
 * 图装配(骨架版):拓扑、状态键策略、检查点——"怎么把图拼出来"都在这里;
 * 执行编排(跑图/事件/运行表/停止/释放)在 GraphService;骨架期图无节点
 */
@Configuration
public class GraphConfig {

	/** 检查点:MySQL 存档(系统库);"跑完即释放"的时机由 GraphService 编排 */
	@Bean
	public BaseCheckpointSaver checkpointSaver(DataSource dataSource) {
		return MysqlSaver.builder().dataSource(dataSource).build();
	}

	@Bean
	public CompiledGraph databuddyGraph(BaseCheckpointSaver checkpointSaver) throws GraphStateException {
		KeyStrategyFactory keyStrategyFactory = () -> Map.of(
				GraphKeys.INPUT, KeyStrategy.REPLACE,
				GraphKeys.HISTORY, KeyStrategy.REPLACE,
				GraphKeys.FINAL_ANSWER, KeyStrategy.REPLACE);
		return new StateGraph("databuddy", keyStrategyFactory)
			.addEdge(START, END) // 骨架:无节点,入口直达终点;节点接入后替换为 START → 首节点 → … → END
			.compile(CompileConfig.builder()
				.saverConfig(SaverConfig.builder().register(checkpointSaver).build())
				.build());
	}

}
