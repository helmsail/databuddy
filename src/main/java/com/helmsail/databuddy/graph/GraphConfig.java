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
 * 图骨架:当前无业务节点(START 直连 END),接入首节点时把 START 边改指向它。
 * 检查点保存:MysqlSaver 挂系统库持久化(构造时自动建表;系统库不可用则启动直接失败)
 */
@Configuration
public class GraphConfig {

	@Bean
	public CompiledGraph dataBuddyGraph(BaseCheckpointSaver checkpointSaver) throws GraphStateException {
		KeyStrategyFactory keyStrategyFactory = () -> Map.of(
				GraphKeys.INPUT, KeyStrategy.REPLACE,
				GraphKeys.FINAL_ANSWER, KeyStrategy.REPLACE);

		return new StateGraph("databuddy", keyStrategyFactory)
			.addEdge(START, END)
			.compile(CompileConfig.builder()
				.saverConfig(SaverConfig.builder().register(checkpointSaver).build())
				.build());
	}

	/** 检查点保存器:MysqlSaver(自动建 GRAPH_THREAD / GRAPH_CHECKPOINT 表);无内存回退,连接不可用直接报错 */
	@Bean
	public BaseCheckpointSaver checkpointSaver(DataSource dataSource) {
		return MysqlSaver.builder().dataSource(dataSource).build();
	}

}
