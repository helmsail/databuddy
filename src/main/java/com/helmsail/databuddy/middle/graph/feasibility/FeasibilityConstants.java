package com.helmsail.databuddy.middle.graph.feasibility;

/**
 * 可行性域常量(唯一来源):节点 ID 与判定取值
 * (requirement_type 取值与 feasibility-assessment 提示词输出一致:英文小写 data_analysis / need_clarification)
 */
public final class FeasibilityConstants {

	/** 可行性评估节点(数据链第五节点) */
	public static final String FEASIBILITY_ASSESSMENT = "feasibility-assessment";

	/** 判定取值:可分析 */
	public static final String DATA_ANALYSIS = "data_analysis";

	/** 判定取值:需要澄清 */
	public static final String NEED_CLARIFICATION = "need_clarification";

	private FeasibilityConstants() {
	}

}
