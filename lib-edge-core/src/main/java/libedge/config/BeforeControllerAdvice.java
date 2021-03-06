package libedge.config;

import com.google.common.collect.ImmutableMap;
import libedge.annotations.Authentication;
import libedge.annotations.PipeConfig;
import libedge.annotations.RateLimit;
import libedge.annotations.SetHeaders;
import libedge.domain.exceptions.GenericException;
import libedge.domain.exceptions.TooManyRequestsException;
import libedge.domain.visitlog.TemplateEnum;
import libedge.services.impl.PipeService;
import libedge.services.impl.RateLimiterService;
import libedge.utils.RequestHelper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Created by Mike He on 4/28/2017.
 */

@Component
@Aspect
@Slf4j
public class BeforeControllerAdvice {

	private static final String ENV_APPLICATION_NAME = "application.name";
	private static final String REPLENISH_RATE_KEY = "replenishRate";
	private static final String BURST_CAPACITY_KEY = "burstCapacity";

	@Autowired
	private PipeService pipeService;

	@Autowired
	private RateLimiterService rateLimiterService;

	@Autowired
	private Environment environment;


	private ThreadLocal<Long> startTime = new ThreadLocal<>();
	private ThreadLocal<Map<String, String>> requestInfo = new ThreadLocal<>();

	@Pointcut("@annotation(libedge.annotations.PipeConfig)")
	public void pipeConfig() {
	}

	@Pointcut("@annotation(libedge.annotations.RateLimit)")
	public void rateLimit() {
	}

	@Pointcut("@annotation(libedge.annotations.Authentication)")
	public void authentication() {
	}

	@Pointcut("@annotation(org.springframework.security.access.prepost.PreAuthorize)")
	public void authorization() {
	}

	@Pointcut("@annotation(libedge.annotations.SetHeaders)")
	public void setHeader() {
	}

	@Pointcut("pipeConfig() || rateLimit() || authentication() || authorization() || setHeader()")
	public void init() {
	}

	@Before("init()")
	public void filterBeforeHandling(JoinPoint joinPoint) throws Exception {
		log.debug("before handing");

		//得到request
		ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
		HttpServletRequest request = attributes.getRequest();

		Map<String, String> requestInfoMap = new LinkedHashMap<>();
		String clientIpAddr = RequestHelper.getRealIp(request);
		String requestUri = request.getRequestURI();
		String requestMethod = request.getMethod();

		int size = 0;
		requestInfoMap.put(TemplateEnum.REMOTE_HOST, clientIpAddr);
		requestInfoMap.put(TemplateEnum.REQUEST_METHOD, requestMethod);
		requestInfoMap.put(TemplateEnum.RESPONSE_BODY_SIZE, String.valueOf(size));
		requestInfoMap.put(TemplateEnum.REQUEST_URI, requestUri);
		requestInfoMap.put(TemplateEnum.SERVICE_NAME, environment.getProperty(ENV_APPLICATION_NAME));
		requestInfo.set(requestInfoMap);

		startTime.set(System.currentTimeMillis());
	}

	@Around("init()")
	public Object filterAroundHandling(ProceedingJoinPoint joinPoint) throws Throwable {
		log.debug("around handing");

		//接收到请求,记录请求内容
		ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
		HttpServletRequest request = attributes.getRequest();
		HttpServletResponse response = attributes.getResponse();

		//得到action方法
		MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
		Method action = methodSignature.getMethod();
		//得到controller
		Class controllerClass = methodSignature.getDeclaringType();

		//是否标注了@Authentication
		if (action.getAnnotation(Authentication.class) != null) {
			boolean hasUserInfo = request.getSession().getAttribute("uid") != null
							&& StringUtils.isNotBlank((String) request.getSession().getAttribute("uid"));
			if (!hasUserInfo) {
				log.error("Failed authenticate in lib-edge around handing.");
				throw new GenericException("2411006", "unauthenticated");
			}
		}

		//流控
		if (action.getAnnotation(RateLimit.class) != null) {
			int rate = -1, capacity = -1;
			RateLimit rateLimit = (RateLimit) controllerClass.getAnnotation(RateLimit.class);
			if (rateLimit != null) {
				rate = rateLimit.rate();
				capacity = rateLimit.capacity();
			}
			rateLimit = action.getAnnotation(RateLimit.class);
			if (rateLimit.rate() != -1) {
				rate = rateLimit.rate();
				capacity = rateLimit.capacity();
			}
			if (rate == -1) {
				throw new GenericException("1911010", "invalid RateLimiter parameter[rate]");
			}
			String clientIp = request.getRemoteAddr();
			String requestAction = action.toString();
			if (!rateLimiterService.isAllowed(clientIp, requestAction,
							ImmutableMap.of(REPLENISH_RATE_KEY, rate, BURST_CAPACITY_KEY, capacity))) {
			  log.error("too many requests!");
				throw new TooManyRequestsException("too many requests!");
			}
		}

		//替换response header内容
		SetHeaders setHeaders;
		if ((setHeaders = action.getAnnotation(SetHeaders.class)) != null) {
			for (String value : setHeaders.value()) {
				String[] header = value.split(":", 2);
				response.setHeader(header[0], header[1]);
			}
		}

		//得到参数中标注了@RequestBody的参数内容
		List<Object> requestBodyList = new ArrayList<>();

		Parameter[] parameters = action.getParameters();
		Object[] parameterContent = joinPoint.getArgs();

		for (int i = 0; i < parameters.length; i++) {
			if (parameters[i].getAnnotation(RequestBody.class) != null) {
				requestBodyList.add(parameterContent[i]);
			}
		}

		//触发action, 完成参数校验部分
		Object object = joinPoint.proceed();
		log.debug("local response: {}", object);

		//透传
		PipeConfig pipeConfig = action.getAnnotation(PipeConfig.class);
		if (pipeConfig != null) {
			object = pipeService.penetrate(request, response, action, controllerClass, requestBodyList);
		}
		log.debug("penetration response: {}", object);

		return object;
	}

	@AfterThrowing(value = "init()", throwing = "ex")
	private void filterAfterThrowing(Throwable ex) {
		log.warn("has some exception.", ex);
	}

}