package com.helmsail.databuddy.memory;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.helmsail.databuddy.aimodel.AiModelServiceFactory;

import lombok.extern.slf4j.Slf4j;

/**
 * 会话记忆服务:跨轮记忆的唯一出入口(窗口 + 摘要),独立于图与检查点。
 * 存储为 session_memory 表(一行 = 一轮对话;超窗老轮由 AI 压成一条摘要条目);
 * 组件是"哑"的且零内存状态:存取时机全部由外部显式调用——
 * 进图前 buildContext(读);成功收尾 finishTurn(写);被拒重来 rollbackTurn(退);
 * 停止/出错不落库:不需要调任何方法,记忆天然保持干净。
 * 同一会话同时只允许一轮(由调用方保证)
 */
@Slf4j
@Component
public class SessionMemoryService {

	/** 窗口:保留的原文轮数;超出的在收尾时压进摘要 */
	private static final int WINDOW_TURNS = 10;

	/** 单轮落库前的截断长度(防粘贴超长文本) */
	private static final int MAX_QUESTION_CHARS = 1000;

	private static final int MAX_ANSWER_CHARS = 500;

	/** 摘要上限(提示词约束 + 落库前兜底截断) */
	private static final int MAX_SUMMARY_CHARS = 300;

	/** 条目类型(kind 取值;与建表脚本注释一致) */
	private static final String KIND_TURN = "turn";

	private static final String KIND_SUMMARY = "summary";

	/** 压缩提示词(起步放代码;要在线调再挪进提示词表) */
	private static final String SUMMARY_PROMPT = """
			你是对话压缩助手。把"已有摘要"与"新增对话"合并压缩为一段不超过 300 字的摘要,
			保留:讨论主题、数据口径(库/表/指标/时间范围)、已达成的结论;丢弃寒暄与重复内容。
			只输出摘要文本,不要任何前缀、标题或解释。
			""";

	private final SessionMemoryMapper mapper;

	private final AiModelServiceFactory aiModelServiceFactory;

	public SessionMemoryService(SessionMemoryMapper mapper, AiModelServiceFactory aiModelServiceFactory) {
		this.mapper = mapper;
		this.aiModelServiceFactory = aiModelServiceFactory;
	}

	/** 进图前:构建上文文本(【此前对话摘要】+ 最近窗口轮,逐行"用户: xx / 助手: xx");无记忆返回 "(无)" */
	public String buildContext(String sessionId) {
		StringBuilder context = new StringBuilder();
		SessionMemory summary = mapper.selectLatestSummary(sessionId);
		if (summary != null) {
			context.append("【此前对话摘要】").append(summary.getAnswer());
		}
		List<SessionMemory> turns = mapper.selectRecentTurns(sessionId, WINDOW_TURNS);
		for (int i = turns.size() - 1; i >= 0; i--) { // 按 id 倒序取回,反转成时间正序拼
			SessionMemory turn = turns.get(i);
			if (context.length() > 0) {
				context.append("\n");
			}
			context.append("用户: ").append(turn.getQuestion()).append("\n助手: ").append(turn.getAnswer());
		}
		return context.length() == 0 ? "(无)" : context.toString();
	}

	/** 成功收尾(只有成功才调):输出为空整轮跳过(如 data_analysis 暂无文本);否则落一行原文轮,超窗老轮压进摘要 */
	public void finishTurn(String sessionId, String question, String answer) {
		if (!StringUtils.hasText(answer)) {
			return;
		}
		SessionMemory turn = new SessionMemory();
		turn.setSessionId(sessionId);
		turn.setKind(KIND_TURN);
		turn.setQuestion(truncate(question, MAX_QUESTION_CHARS));
		turn.setAnswer(truncate(answer.trim(), MAX_ANSWER_CHARS));
		mapper.insert(turn);
		compressOverflow(sessionId);
	}

	/** 被拒重来:回退最后一轮原文(被拒的轮必在窗口内,不会已并入摘要) */
	public void rollbackTurn(String sessionId) {
		mapper.deleteLatestTurn(sessionId);
	}

	/** 清某线程键的全部记忆条目(删会话编排中由图侧接口调用;本组件不判断时机) */
	public void deleteBySession(String sessionId) {
		mapper.deleteBySession(sessionId);
	}

	/** 溢出压缩:窗口外最老的若干轮 + 旧摘要 → AI 压成新摘要;失败跳过(下轮再试) */
	private void compressOverflow(String sessionId) {
		List<SessionMemory> recent = mapper.selectRecentTurns(sessionId, WINDOW_TURNS + 1);
		if (recent.size() <= WINDOW_TURNS) {
			return;
		}
		List<SessionMemory> overflow = recent.subList(WINDOW_TURNS, recent.size()); // id 倒序:窗口外最老的若干轮
		SessionMemory oldSummary = mapper.selectLatestSummary(sessionId);
		String summaryText;
		try {
			summaryText = summarize(oldSummary == null ? null : oldSummary.getAnswer(), overflow);
		}
		catch (Exception e) {
			log.warn("记忆压缩失败,本轮跳过(下轮重试): sessionId={}", sessionId, e);
			return;
		}
		if (!StringUtils.hasText(summaryText)) {
			log.warn("记忆压缩返回空,本轮跳过(下轮重试): sessionId={}", sessionId);
			return;
		}
		SessionMemory summary = new SessionMemory();
		summary.setSessionId(sessionId);
		summary.setKind(KIND_SUMMARY);
		summary.setAnswer(truncate(summaryText, MAX_SUMMARY_CHARS));
		mapper.insert(summary);
		// 先插后删:崩在中间最多多留一条旧摘要,读时取最新、下轮顺手清掉
		mapper.deleteOverflowTurns(sessionId, overflow.get(0).getId()); // 以此 id 为界:更老的原文轮全部清掉
		mapper.deleteOldSummaries(sessionId, summary.getId());
	}

	/** AI 压缩:旧摘要(可无)+ 溢出轮 → 一段新摘要 */
	private String summarize(String oldSummary, List<SessionMemory> overflow) {
		StringBuilder dialog = new StringBuilder();
		for (int i = overflow.size() - 1; i >= 0; i--) { // 按 id 倒序取回,反转成时间正序
			SessionMemory turn = overflow.get(i);
			dialog.append("用户: ").append(turn.getQuestion()).append("\n助手: ").append(turn.getAnswer()).append("\n");
		}
		String user = (StringUtils.hasText(oldSummary) ? "已有摘要:\n" + oldSummary + "\n\n" : "")
				+ "新增对话:\n" + dialog;
		return aiModelServiceFactory.getChatClient().prompt().user(SUMMARY_PROMPT + "\n\n" + user).call().content();
	}

	/** 截断;null 原样返回 */
	private static String truncate(String text, int maxChars) {
		if (text == null || text.length() <= maxChars) {
			return text;
		}
		return text.substring(0, maxChars);
	}

}
