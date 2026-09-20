package com.helmsail.databuddy.result;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 标准分页载荷:分页能力引入时作为 ApiResponse 的 data 形状(约定见 ApiResponse 类注释)。
 * 当前列表接口均为全量返回,本类为规范定稿保留件
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

	/**
	 * 数据列表
	 */
	private List<T> data;

	/**
	 * 总记录数
	 */
	private Long total;

	/**
	 * 当前页码
	 */
	private Integer pageNum;

	/**
	 * 每页大小
	 */
	private Integer pageSize;

	/**
	 * 获取总页数(根据总记录数和每页大小实时计算)
	 */
	public Integer getTotalPages() {
		if (total != null && pageSize != null && pageSize > 0) {
			return (int) Math.ceil((double) total / pageSize);
		}
		return 0;
	}

}
