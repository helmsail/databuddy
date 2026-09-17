package com.helmsail.databuddy.result;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
