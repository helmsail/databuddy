package com.helmsail.databuddy.memory;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import com.helmsail.databuddy.aimodel.AiModelServiceFactory;
import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * 记忆服务:memory 包唯一入口——两类记忆、两张表(表只存,策略全在此)。
 * 读:buildContext 一次取齐两段(会话上文 + agent 沉淀清单)供进图前注入;写与清分属各自记忆,方法分开。
 * ①会话记忆(session_memory 单表:一行 = 一段自带标识的文本——"用户: …" / "助手: …" / 摘要行"【此前对话摘要】…",
 *   数据侧无任何行类型概念):跨图运行的会话上下文。
 *   写:成功收尾追加"用户 / 助手"两行,超窗(20 条消息)即压缩——最老若干行(含旧摘要行,若有)交给 LLM 合并成一行摘要,写回最老行、删其余;
 *   清:deleteBySession(整会话行)。
 *   压缩失败保留原行(下次溢出重试,不丢消息);"写回摘要 + 删被压缩行"在同一事务(防半程造成重复压缩);
 *   读写时机由外部显式调用(停止 / 出错不落库,会话记忆天然干净)。
 * ②agent 沉淀记忆(agent_memory):AI 工具(AgentMemoryTools)按需沉淀的口径 / 规则 / 偏好,用户可看可改可删;
 *   纯 CRUD + 上限保护,不向量化——要进检索由用户在术语域手动升级(转正式知识)
 */
@Slf4j
@Service
public class MemoryService {

	/** 会话记忆窗口:10 轮对话 = 20 条消息(表内至多 21 行 = 20 条消息 + 1 行摘要) */
	private static final int WINDOW_MESSAGES = 20;

	/** 摘要行标识:写在内容开头,压缩时随内容一起回喂 LLM(读侧直接拼接,无需识别) */
	private static final String SUMMARY_TAG = "【此前对话摘要】";

	/** 摘要上限(提示词约束 + 落库前兜底截断) */
	private static final int MAX_SUMMARY_CHARS = 300;

	/** 单轮落库前的截断长度(防粘贴超长文本) */
	private static final int MAX_QUESTION_CHARS = 1000;

	private static final int MAX_ANSWER_CHARS = 500;

	/** 压缩提示词(起步放代码;要在线调再挪进提示词表) */
	private static final String SUMMARY_PROMPT = """
			你是对话压缩助手。把下面这段较早的对话记录合并压缩为一段不超过 300 字的摘要,
			记录中可能有一行以"【此前对话摘要】"开头,那是此前的压缩结果,请与新的对话一并合并。
			保留:讨论主题、数据口径(库/表/指标/时间范围)、已达成的结论;丢弃寒暄与重复内容。
			只输出摘要文本,不要任何前缀、标题或解释。
			""";

	/** agent 沉淀记忆:单 agent 条数上限(防清单注入膨胀;到限提示先合并或删除) */
	private static final int MAX_ENTRIES = 50;

	private final SessionMemoryMapper sessionMapper;

	private final AgentMemoryMapper agentMapper;

	private final AiModelServiceFactory aiModelServiceFactory;

	private final TransactionTemplate transactionTemplate;

	public MemoryService(SessionMemoryMapper sessionMapper, AgentMemoryMapper agentMapper,
			AiModelServiceFactory aiModelServiceFactory, PlatformTransactionManager transactionManager) {
		this.sessionMapper = sessionMapper;
		this.agentMapper = agentMapper;
		this.aiModelServiceFactory = aiModelServiceFactory;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	// ============ 读取:进图前两段一次取齐(会话上文 + agent 沉淀清单) ============

	/** 进图前的两段记忆文本:history = 会话上文,agentMemo = agent 沉淀清单 */
	public record MemoryTexts(String history, String agentMemo) {
	}

	/** 进图前:会话上文 + agent 清单一次取齐;sessionId 为 null(轻档流无会话)时会话段为 "(无)" */
	public MemoryTexts buildContext(long agentId, String sessionId) {
		return new MemoryTexts(renderSession(sessionId), renderAgentMemo(agentId));
	}

	// ============ 会话记忆(写 / 清) ============

	/** 成功收尾(只有成功才调):输出为空整轮跳过;否则追加"用户 / 助手"两行,超窗即压缩 */
	public void addTurn(String sessionId, String question, String answer) {
		if (!StringUtils.hasText(answer)) {
			return;
		}
		append(sessionId, "用户: " + truncate(question, MAX_QUESTION_CHARS));
		append(sessionId, "助手: " + truncate(answer.trim(), MAX_ANSWER_CHARS));
		List<SessionMemory> rows = sessionMapper.selectBySession(sessionId);
		if (rows.size() <= WINDOW_MESSAGES + 1) {
			return; // 21 行内(20 条消息 + 1 行摘要位)不压缩
		}
		// 压缩:最老 rows-20 行(其中可能含上一版摘要行)交给 LLM 合并(压缩失败或为空:保留原行,下次溢出重试)
		List<SessionMemory> oldest = rows.subList(0, rows.size() - WINDOW_MESSAGES);
		StringBuilder material = new StringBuilder();
		for (SessionMemory row : oldest) {
			material.append(row.getContent()).append('\n');
		}
		String summary;
		try {
			summary = aiModelServiceFactory.getChatClient().prompt().user(SUMMARY_PROMPT + "\n\n" + material).call().content();
		}
		catch (Exception e) {
			log.warn("记忆压缩失败,保留原行待重试: sessionId={}", sessionId, e);
			return;
		}
		if (!StringUtils.hasText(summary)) {
			log.warn("记忆压缩返回空,保留原行待重试: sessionId={}", sessionId);
			return;
		}
		// 写回最老行(内容替换为摘要行)+ 删其余被压缩行:同一事务(防半程造成重复压缩)
		SessionMemory anchor = oldest.get(0);
		List<Long> ids = oldest.subList(1, oldest.size()).stream().map(SessionMemory::getId).toList();
		String compressed = SUMMARY_TAG + truncate(summary, MAX_SUMMARY_CHARS);
		transactionTemplate.executeWithoutResult(status -> {
			sessionMapper.updateContent(anchor.getId(), compressed);
			sessionMapper.deleteByIds(sessionId, ids);
		});
	}

	/** 清某会话全部记忆(消息 + 摘要行) */
	public void deleteBySession(String sessionId) {
		sessionMapper.deleteBySession(sessionId);
	}

	// ============ agent 沉淀记忆(清单 / 增删改 / 清) ============

	/** 某 agent 的记忆清单(按沉淀先后) */
	public List<AgentMemory> list(long agentId) {
		return agentMapper.selectByAgent(agentId);
	}

	/** 沉淀一条记忆:内容必填;超上限拒绝(提示先合并 / 删除) */
	public AgentMemory add(long agentId, String content) {
		validate(content);
		if (agentMapper.selectByAgent(agentId).size() >= MAX_ENTRIES) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "记忆已达上限(" + MAX_ENTRIES + " 条),请先合并或删除旧条目");
		}
		AgentMemory memory = new AgentMemory();
		memory.setAgentId(agentId);
		memory.setContent(content.trim());
		agentMapper.insert(memory);
		log.info("记忆沉淀: agent={} (#{})", agentId, memory.getId());
		return memory;
	}

	/** 修正一条记忆(按 agent + id 定位):内容必填;不存在抛 404 */
	public AgentMemory update(long agentId, long id, String content) {
		validate(content);
		requireMemory(agentId, id);
		agentMapper.update(agentId, id, content.trim());
		return requireMemory(agentId, id);
	}

	/** 删除一条记忆(按 agent + id 定位);不存在抛 404 */
	public void delete(long agentId, long id) {
		requireMemory(agentId, id);
		agentMapper.deleteById(agentId, id);
		log.info("记忆删除: agent={} (#{})", agentId, id);
	}

	/** 清某 agent 全部记忆(agent 级联删除编排调用) */
	public void deleteByAgent(long agentId) {
		agentMapper.deleteByAgent(agentId);
	}

	// ============ 私有实现 ============

	/** 会话上文:全部行按顺序拼接;无会话 / 无语录返回 "(无)" */
	private String renderSession(String sessionId) {
		if (!StringUtils.hasText(sessionId)) {
			return "(无)";
		}
		List<String> lines = sessionMapper.selectBySession(sessionId)
			.stream()
			.map(SessionMemory::getContent)
			.toList();
		return lines.isEmpty() ? "(无)" : String.join("\n", lines);
	}

	/** agent 清单文本:每行 "序号. [id=主键] 内容";无沉淀返回 "(无)" */
	private String renderAgentMemo(long agentId) {
		List<AgentMemory> rows = agentMapper.selectByAgent(agentId);
		if (rows.isEmpty()) {
			return "(无)";
		}
		StringBuilder text = new StringBuilder();
		int index = 1;
		for (AgentMemory row : rows) {
			text.append(index++).append(". [id=").append(row.getId()).append("] ").append(row.getContent()).append('\n');
		}
		return text.toString().trim();
	}

	/** 追加一行("用户: …" / "助手: …" / 摘要行) */
	private void append(String sessionId, String content) {
		SessionMemory row = new SessionMemory();
		row.setSessionId(sessionId);
		row.setContent(content);
		sessionMapper.insert(row);
	}

	/** 取记忆行(按 agent + id,清单筛取);不存在抛 404 */
	private AgentMemory requireMemory(long agentId, long id) {
		AgentMemory memory = agentMapper.selectByAgent(agentId).stream()
			.filter(row -> row.getId() == id)
			.findFirst()
			.orElse(null);
		if (memory == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "记忆不存在: " + id);
		}
		return memory;
	}

	/** 校验:记忆内容必填 */
	private void validate(String content) {
		if (!StringUtils.hasText(content)) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "记忆内容不能为空");
		}
	}

	/** 截断 */
	private static String truncate(String text, int maxChars) {
		return text.length() <= maxChars ? text : text.substring(0, maxChars);
	}

}

