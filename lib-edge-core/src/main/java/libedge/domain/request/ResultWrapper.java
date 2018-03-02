package libedge.domain.request;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @param <T>
 * @author yrw
 */
@Data
@NoArgsConstructor
public class ResultWrapper<T> {
	private int code;
	private String message;
	private T data;

	@SuppressWarnings("unchecked")
	public ResultWrapper(T data) {
		this.code = 1000000;
		this.message = "OK";
		this.data = data;
	}
}