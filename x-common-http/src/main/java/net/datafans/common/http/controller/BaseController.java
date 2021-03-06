package net.datafans.common.http.controller;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.datafans.common.exception.ClientException;
import net.datafans.common.exception.ServerException;
import net.datafans.common.http.annotation.Overload;
import net.datafans.common.http.constant.CommonAttribute;
import net.datafans.common.http.constant.CommonParameter;
import net.datafans.common.http.entity.AccessLog;
import net.datafans.common.http.exception.AuthFailedException;
import net.datafans.common.http.exception.OverloadSufferException;
import net.datafans.common.http.handler.AccessLogHandler;
import net.datafans.common.http.handler.ErrorCodeHandler;
import net.datafans.common.http.handler.ServerConfigHandler;
import net.datafans.common.http.interceptor.AccessLogInterceptor;
import net.datafans.common.http.manager.OverloadManager;
import net.datafans.common.http.manager.VersionManager;
import net.datafans.common.http.response.ErrorResponse;
import net.datafans.common.util.LogUtil;

import org.apache.commons.lang.math.NumberUtils;
import org.eclipse.jetty.http.HttpStatus;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.stereotype.Controller;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.multiaction.NoSuchRequestHandlingMethodException;

import com.alibaba.fastjson.JSON;

@Controller
public class BaseController {

    @Autowired
    private VersionManager versionManager;

    @Autowired
    private OverloadManager overloadManager;

    @Autowired(required = false)
    private ErrorCodeHandler errorCodeHandler;

    @Autowired(required = false)
    private AccessLogHandler logHandler;


    @Autowired(required = false)
    private ServerConfigHandler serverConfigHandler;

    @RequestMapping(value = "/")
    public Object defaultObject(HttpServletRequest request) {
        return null;
    }

    @PostConstruct
    public void initAnnotation() {

        String path = "";
        if (this.getClass().isAnnotationPresent(RequestMapping.class)) {
            RequestMapping mapping = this.getClass().getAnnotation(RequestMapping.class);
            String[] values = mapping.value();
            if (values != null && values.length > 0) {
                path += values[0];
            }
        }

        Method[] methods = this.getClass().getMethods();
        if (methods != null) {
            for (Method method : methods) {

                if (method.isAnnotationPresent(RequestMapping.class)) {
                    RequestMapping mapping = method.getAnnotation(RequestMapping.class);
                    String[] values = mapping.value();
                    if (values != null && values.length > 0) {
                        String num = values[0].replace("/", "");
                        if (!num.isEmpty()) {
                            versionManager.register(path, NumberUtils.toInt(num));

                            String prefixPath = path.endsWith("/") ? path : path + "/";
                            String fullpath = prefixPath + NumberUtils.toInt(num);
                            if (method.isAnnotationPresent(Overload.class)) {
                                Overload overload = method.getAnnotation(Overload.class);
                                int threshold = overload.threshold();
                                TimeUnit timeUnit = overload.timeUnit();
                                overloadManager.register(fullpath, threshold, timeUnit);

                            }
                        }

                    }
                }

            }
        }

    }

    @ExceptionHandler(Exception.class)
    public void exceptionHandler(Exception ex, HttpServletResponse response, HttpServletRequest request)
            throws IOException {

        LogUtil.error(BaseController.class, "BASE_ERROR", ex);

        response.setStatus(HttpStatus.OK_200);

        ErrorResponse rp = new ErrorResponse();
        fillErrorResponse(ex, rp);

        request.setAttribute(CommonAttribute.RESPONSE_STATUS_CODE, rp.getErrorCode());
        request.setAttribute(CommonAttribute.RESPONSE_STATUS_MSG, rp.getErrorMsg());

        logError(request);
        sendErrorResponse(rp, response);
    }

    private void logError(HttpServletRequest request) {

        Long startTime = NumberUtils.toLong(request.getAttribute(CommonAttribute.REQUEST_START_TIME).toString());
        Long endTime = System.currentTimeMillis();
        int timeCost = (int) (endTime - startTime);
        LogUtil.info(this.getClass(), "REQUEST_TIME: " + timeCost);

        AccessLog log = new AccessLog();
        log.setLogId(1);
        log.setAccessTime(System.currentTimeMillis());
        log.setClientHost(AccessLogInterceptor.getRemoteAddrIp(request));

        Object userId = request.getAttribute(CommonAttribute.USER_ID);
        if (userId != null) {
            log.setClientUniqueId(NumberUtils.toInt(userId.toString()));
        }

        log.setParams(JSON.toJSONString(request.getParameterMap()));
        log.setPath(request.getRequestURI());
        log.setApiVersion(NumberUtils.toInt(request.getParameter(CommonParameter.API_VERSION)));
        log.setPlatform(NumberUtils.toInt(request.getParameter(CommonParameter.PLATFORM)));
        if (serverConfigHandler != null)
            log.setServerId(serverConfigHandler.getServerId());
        else
            log.setServerId("");
        log.setTimeCost(timeCost);

        Object errorCode = request.getAttribute(CommonAttribute.RESPONSE_STATUS_CODE);
        if (errorCode != null) {
            log.setErrorCode(NumberUtils.toInt(errorCode.toString()));
        }

        Object errorMsg = request.getAttribute(CommonAttribute.RESPONSE_STATUS_MSG);
        if (errorMsg != null) {
            log.setErrorMsg(errorMsg.toString());
        }

        if (logHandler != null) {
            logHandler.handle(log);
        }
    }

    private void fillErrorResponse(Exception ex, ErrorResponse rp) {
        if (ex instanceof ConversionNotSupportedException) {
            // 500 (Internal Server Error)
            rp.setErrorCode(500);
            rp.setErrorMsg("server error");
        } else if (ex instanceof HttpMediaTypeNotAcceptableException) {
            // 406 (Not Acceptable)
            rp.setErrorCode(406);
            rp.setErrorMsg(ex.getMessage());
        } else if (ex instanceof HttpMediaTypeNotSupportedException) {
            // 415 (Unsupported Media Type)
            rp.setErrorCode(415);
            rp.setErrorMsg(ex.getMessage());
        } else if (ex instanceof HttpMessageNotReadableException) {
            // 400 (Bad Request)
            rp.setErrorCode(400);
            rp.setErrorMsg(ex.getMessage());
        } else if (ex instanceof HttpMessageNotWritableException) {
            // 500 (Internal Server Error)
            rp.setErrorCode(500);
            rp.setErrorMsg("server error");
        } else if (ex instanceof HttpRequestMethodNotSupportedException) {
            // 405 (Method Not Allowed)
            rp.setErrorCode(405);
            rp.setErrorMsg(ex.getMessage());
        } else if (ex instanceof MissingServletRequestParameterException) {
            // 400 (Bad Request)
            //获得具体的参数名称
            String msg = ex.getMessage();
            int first = msg.indexOf("'")+1;
            int end = msg.lastIndexOf("'");
            String fieldName = msg.substring(first, end);
            rp.setErrorCode(400);
            rp.setErrorMsg("field "+fieldName+" is required");
        } else if (ex instanceof NoSuchRequestHandlingMethodException) {
            // 404 (Not Found)
            rp.setErrorCode(404);
            rp.setErrorMsg(ex.getMessage());
        } else if (ex instanceof TypeMismatchException) {
            // 400 (Bad Request)
            rp.setErrorCode(400);
            rp.setErrorMsg("field type mismatch");
        } else if (ex instanceof ServerException) {
            // server exception
            ServerException serverException = (ServerException) ex;
            rp.setErrorCode(serverException.getErrorCode());
            rp.setErrorMsg(serverException.getErrorMsg());
        } else if (ex instanceof ClientException) {
            // client exception
            ClientException clientException = (ClientException) ex;
            rp.setErrorCode(clientException.getErrorCode());
            rp.setErrorMsg(clientException.getErrorMsg());
//			if (errorCodeHandler != null) {
//				rp.setErrorMsg(errorCodeHandler.onError(clientException.getErrorCode()).getErrorMsg());
//			}

        } else if (ex instanceof AuthFailedException) {
            // auth fail runtime exception
            if (errorCodeHandler != null) {
                rp.setErrorCode(errorCodeHandler.onAuthFail().getErrorCode());
                rp.setErrorMsg(errorCodeHandler.onAuthFail().getErrorMsg());
            } else {
                rp.setErrorCode(2001);
                rp.setErrorMsg("auth failed");
            }
        } else if (ex instanceof OverloadSufferException) {
            // overload suffer runtime exception
            if (errorCodeHandler != null) {
                rp.setErrorCode(errorCodeHandler.onOverload().getErrorCode());
                rp.setErrorMsg(errorCodeHandler.onOverload().getErrorMsg());
            } else {
                rp.setErrorCode(1001);
                rp.setErrorMsg("system overload");
            }
        } else {
            rp.setErrorCode(500);
            rp.setErrorMsg("server error");
        }
    }

    private void sendErrorResponse(ErrorResponse rp, HttpServletResponse response) throws IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        PrintWriter writer = response.getWriter();
        String json = JSON.toJSONString(rp);
        writer.write(json);
        writer.flush();
    }
}
