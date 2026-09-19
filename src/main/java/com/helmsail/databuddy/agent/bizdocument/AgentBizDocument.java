package com.helmsail.databuddy.agent.bizdocument;

import java.time.LocalDateTime;

import com.helmsail.databuddy.agent.EmbeddingStatus;
import com.helmsail.databuddy.storage.StorageType;
import com.helmsail.databuddy.vectorize.splitter.SplitterType;

import lombok.Data;

/**
 * agent 业务文档(一行 = 一份文档);文件本体在 storage 包(路径与类型存本表),上传后异步切分向量化
 */
@Data
public class AgentBizDocument {

	/** 主键(新增时由数据库回填) */
	private Long id;

	/** 所属 agent */
	private Long agentId;

	/** 文档名(同 agent 下唯一;缺省为上传文件名,可改名) */
	private String name;

	/** 存储类型(FileStorage 分发键,当前 LOCAL) */
	private StorageType storageType;

	/** 存储路径(相对存储根,如 docs/1/合同.txt) */
	private String storagePath;

	/** 切分策略(WHOLE / PARAGRAPH / MARKDOWN / TOKEN;变更后自动重入向量) */
	private SplitterType splitterType;

	/** 向量化状态(PENDING / SYNCED / FAILED) */
	private EmbeddingStatus embeddingStatus;

	/** 最近一次向量化失败原因(成功时清空) */
	private String errorMsg;

	/** 创建时间 */
	private LocalDateTime createTime;

	/** 更新时间 */
	private LocalDateTime updateTime;

}
