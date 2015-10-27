package com.github.filter;

import com.alibaba.fastjson.JSON;
import com.alibaba.rocketmq.common.message.Message;
import com.github.autoconf.helper.ConfigHelper;
import com.github.filter.helpers.FilterHelpers;
import com.github.filter.helpers.Pair;
import com.github.filter.io.BufferedResponseWrapper;
import com.github.filter.io.CharsetDetectRequestWrapper;
import com.github.filter.reporter.PageStatusReporter;
import com.github.trace.TraceContext;
import com.github.trace.bean.AccessBean;
import com.github.trace.sender.RocketMqSender;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.*;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;

/**
 * 核心Filter
 * Created by lirui on 2014/7/17.
 */
public class CoreFilter implements Filter {
  private static final Logger LOG = LoggerFactory.getLogger(CoreFilter.class);
  private static final String alreadyFilteredAttributeName = "core-filter.FILTERED";
  private static Set<String> STATIC_POSTFIX;

  static {
    String ext = "txt,css,js,gif,png,jpg,jpeg,swf,ico,flv,exe,mp3,mp4,wma,apk,rar,zip,tar.gz,tgz,7z";
    STATIC_POSTFIX = ImmutableSet.copyOf(Splitter.on(',').trimResults().omitEmptyStrings().split(ext));
  }

  private boolean enableGZip = true;

  @Override
  public void init(FilterConfig conf) throws ServletException {
    if ("false".equalsIgnoreCase(conf.getInitParameter("gzip"))) {
      enableGZip = false;
    }
  }

  @Override
  public void destroy() {
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
    HttpServletRequest req = (HttpServletRequest) request;
    HttpServletResponse res = (HttpServletResponse) response;
    //设定请求开始时间
    FilterHelpers.getRequestTime(req);

    if (request.getAttribute(alreadyFilteredAttributeName) != null || shouldNotFilter(req)) {
      // Proceed without invoking this filter...
      chain.doFilter(request, response);
    } else {
      // Do invoke this filter...
      request.setAttribute(alreadyFilteredAttributeName, Boolean.TRUE);
      CharsetDetectRequestWrapper reqWrapper = new CharsetDetectRequestWrapper(req);
      boolean gzip = false;
      //是否启用GZip压缩
      if (enableGZip) {
        String encoding = req.getHeader("Accept-Encoding");
        if (encoding != null && encoding.contains("gzip")) {
          gzip = true;
        }
      }
      BufferedResponseWrapper resWrapper = new BufferedResponseWrapper(res, gzip);
      try {
        //XXX: 生成traceId，需要请求_traceId的参数，所以必须放到CharsetEncodingHandler后面
        fillTraceContext(reqWrapper);
        chain.doFilter(reqWrapper, resWrapper);
        if (TraceContext.get().isColor()) {
          Cookie cookie = new Cookie("_color", "1");
          cookie.setMaxAge(3600);
          cookie.setPath("/");
          resWrapper.addCookie(cookie);
        }
      } catch (Exception e) {
        LOG.error("{}", req.getRequestURL(), e);
        resWrapper.setStatus(500);
        //这里把异常抛出去，针对服务端异常，接入层nginx可以统计到，否则就统计不到
        throw new ServletException(req.getRequestURL() + ", message: " + e.getMessage(), e.getCause());
      } finally {
        request.removeAttribute(alreadyFilteredAttributeName);
        try {
          if (resWrapper.getLocation() != null) {
            res.sendRedirect(resWrapper.getLocation());
          } else {
            setContentType(res);
            try {
              copyResponse(res, resWrapper);
            } catch (Exception e) {
              long cost = FilterHelpers.getCostTime(req);
              LOG.error("{}, cost={}ms", req.getRequestURL(), cost, e);
            }
          }
        } finally {
          TraceContext c = TraceContext.get();
          TraceContext.remove();
          //染色日志发送到总线上
          if (c.isColor()) {
            sendTrace(req, c, resWrapper);
          }
          //统计页面状态信息
          PageStatusReporter.getInstance().stat(req, c.getCost(), res.getStatus(), c.isSpider());
        }
      }
    }
  }

  /* 避免中文乱码 */
  private void setContentType(HttpServletResponse res) {
    String contentType = res.getContentType();
    if (Strings.isNullOrEmpty(contentType)) {
      contentType = "text/html; charset=UTF-8";
    }
    final String iso = "iso-8859-1";
    if (!contentType.toLowerCase().contains("charset")) {
      String encoding = res.getCharacterEncoding();
      if (Strings.isNullOrEmpty(encoding) || encoding.toLowerCase().equals(iso)) {
        encoding = "UTF-8";
      }
      contentType += "; charset=" + encoding;
      res.setContentType(contentType);
    } else {
      int pos = contentType.toLowerCase().lastIndexOf(iso);
      if (pos > 0) {
        String mime = contentType.substring(0, pos) + "UTF-8";
        int start = pos + iso.length() + 1;
        if (start < contentType.length()) {
          mime += contentType.substring(start);
        }
        res.setContentType(mime);
      }
    }
  }

  /* 发送trace日志到总线上 */
  private void sendTrace(HttpServletRequest req, TraceContext c, BufferedResponseWrapper res) {
    AccessBean b = new AccessBean();
    b.setStamp(FilterHelpers.getRequestTime(req));
    b.setCost((int) FilterHelpers.getCostTime(req));
    b.setTraceId(c.getTraceId());
    b.setRpcId(c.getParentRpcId());
    b.setClientIp(getRemoteIp(req));
    b.setServerIp(ConfigHelper.getServerInnerIP());
    b.setProfile(ConfigHelper.getProcessInfo().getProfile());
    b.setCode(res.getStatus());
    b.setSize(res.getLength());
    b.setReferer(req.getHeader("Referer"));
    b.setUserAgent(req.getHeader("User-Agent"));
    b.setCookie(req.getHeader("Cookie"));
    b.setUid(FilterHelpers.getUserId(req));
    String url = req.getServerName();
    if (req.getServerPort() != 80) {
      url += ':' + req.getServerPort();
    }
    if (req.getQueryString() != null) {
      url += '?' + req.getQueryString();
    }
    b.setUrl(url);
    Message m = new Message("JinJingAccess", c.getTraceId(), JSON.toJSONBytes(b));
    RocketMqSender.getInstance().asyncSend(m);
  }

  /* 从request中获取IP, 首先从X-Forwarded-For的头信息中提取，否则取直连的IP. */
  public String getRemoteIp(HttpServletRequest r) {
    String ip = r.getHeader("X-Forwarded-For");
    if (ip != null && ip.length() > 7) {
      int pos = ip.indexOf(',');
      if (pos > 0) {
        return ip.substring(0, pos);
      }
      return ip;
    }
    return r.getRemoteAddr();
  }

  private void fillTraceContext(CharsetDetectRequestWrapper reqWrapper) {
    TraceContext traceContext = TraceContext.get();
    Pair<String, String> traceAndStep = FilterHelpers.getTraceIdAndRpcId(reqWrapper);
    traceContext.setTraceId(traceAndStep.first);
    traceContext.setParentRpcId(traceAndStep.second);
    traceContext.setSpider(FilterHelpers.isSpider(reqWrapper));
    traceContext.setColor(FilterHelpers.isColorized(reqWrapper)).setFail(false);
  }

  private void copyResponse(HttpServletResponse res, BufferedResponseWrapper wrapper) throws IOException {
    if (wrapper.isError()) {
      res.setContentType("text/html; charset=UTF-8");
      res.sendError(wrapper.getStatus());
    }
    //发送响应内容
    wrapper.flushBuffer();
    if (wrapper.getLength() > 0) {
      if (wrapper.isGZipped()) {
        res.setHeader("Content-Encoding", "gzip");
      }
      if (!res.isCommitted()) {
        String traceId = TraceContext.get().getTraceId();
        if (!Strings.isNullOrEmpty(traceId)) {
          res.setHeader("x-trace-id", traceId);
        }
        res.setContentLength(wrapper.getLength());
        wrapper.writeTo(res.getOutputStream());
        res.flushBuffer();
      }
    }
  }

  /**
   * 不过滤静态资源，因为BufferedWrapper只支持文本
   *
   * @param req 请求对象
   * @return 如果uri以静态资源后缀结束，则不作过滤	 *
   */
  private boolean shouldNotFilter(HttpServletRequest req) {
    String uri = req.getRequestURI();
    int pos = uri.lastIndexOf('.');
    int begin = pos + 1;
    if (pos != -1 && begin < uri.length()) {
      String postfix = uri.substring(begin);
      return STATIC_POSTFIX.contains(postfix);
    }
    return false;
  }
}
