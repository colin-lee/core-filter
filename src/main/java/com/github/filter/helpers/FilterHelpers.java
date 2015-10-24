package com.github.filter.helpers;

import com.github.autoconf.helper.ConfigHelper;
import com.google.common.base.Charsets;
import com.google.common.hash.Hashing;

import javax.servlet.http.HttpServletRequest;

/**
 * 工具类
 * Created by lirui on 2015-10-24 10:30.
 */
public final class FilterHelpers {
  public static final String REQUEST_TIME_ATTRIBUTE = "REQUEST_TIME_NAME";
  public static final String IS_SPIDER_ATTRIBUTE = "IS_SPIDER_ATTR_NAME";
  public static final String IS_COLOR_ATTRIBUTE = "IS_COLOR_ATTR_NAME";
  public static final String TRACE_ID_ATTRIBUTE = "PV_TRACE_ID";
  public static final String USER_ID_ATTRIBUTE = "PV_USER_ID";

  private FilterHelpers() {
  }

  /**
   * 获取请求开始时间，如果没有就重设为当前时间
   *
   * @param request 请求对象
   * @return
   */
  public static long getRequestTime(HttpServletRequest request) {
    Long time = getAttribute(request, REQUEST_TIME_ATTRIBUTE);
    if (time == null) {
      long now = System.currentTimeMillis();
      request.setAttribute(REQUEST_TIME_ATTRIBUTE, now);
      return now;
    } else {
      return time;
    }
  }

  /**
   * 获取当前请求的服务端处理时间
   *
   * @param request 请求对象
   * @return 如果没有设定开始时间，则返回-1
   */
  public static long getCostTime(HttpServletRequest request) {
    Long time = getAttribute(request, REQUEST_TIME_ATTRIBUTE);
    if (time == null)
      return -1;
    return System.currentTimeMillis() - time;
  }

  /**
   * 读取request属性，判定是否为爬虫，依赖于外部进行setSpider
   *
   * @param request 请求对象
   * @return 是否是爬虫请求
   */
  public static boolean isSpider(HttpServletRequest request) {
    Boolean spider = getAttribute(request, IS_SPIDER_ATTRIBUTE);
    if (spider == null) {
      String ua = request.getHeader("User-Agent");
      if (ua != null) {
        String uaL = ua.toLowerCase();
        if (uaL.contains("spider") || uaL.contains("bot")) {
          setSpider(request, Boolean.TRUE);
          return true;
        }
      }
    }
    return false;
  }

  /**
   * 判断当前请求是否需要染色
   *
   * @param request 请求对象
   * @return 是否是染色请求
   */
  public static boolean isColorized(HttpServletRequest request) {
    Boolean colorized = getAttribute(request, IS_COLOR_ATTRIBUTE);
    if (colorized == null) {
      if (request.getParameter("_color") != null) {
        return true;
      }
      if (request.getHeader("X-MONITOR") != null) {
        return true;
      }
      String cookie = request.getHeader("Cookie");
      if (cookie != null && cookie.contains("; _cookie=1")) {
        return true;
      }
    }
    return false;
  }

  /**
   * 获取traceId，如果从参数传输过来，则直接用，否则就生成一个。保证每个PV都不同
   *
   * @param request 请求对象
   * @return
   */
  public static String getTraceId(HttpServletRequest request) {
    String id = getAttribute(request, TRACE_ID_ATTRIBUTE);
    if (id == null) {
      // 如果是ajax请求，可以通过js透传变量，这样能够把多个ajax请求聚合到一个traceId下
      // 优先从header中获取
      id = request.getHeader("x-trace-id");
      if (id == null || id.length() < 16) {
        id = request.getParameter("_traceId");
      }
      if (id == null || id.length() < 16) {
        StringBuilder sbd = new StringBuilder(128);
        sbd.append(System.currentTimeMillis());
        sbd.append(Thread.currentThread().getId());
        sbd.append(ConfigHelper.getServerInnerIP());
        sbd.append(request.getRequestURI()).append(request.getQueryString());
        id = Hashing.md5().hashString(sbd.toString(), Charsets.UTF_8).toString();
      }
      request.setAttribute(TRACE_ID_ATTRIBUTE, id);
    }
    return id;
  }

  /**
   * 同时获取traceId和step（主要针对ajax），如果从参数传输过来
   * 则直接用，否则就生成一个。保证每个PV
   * ajax会通过 : 区分请求的step编号
   *
   * @param request 请求对象
   * @return 返回trace和step信息
   */
  public static Pair<String, String> getTraceIdAndRpcId(HttpServletRequest request) {
    String raw = getTraceId(request);
    String traceId = raw;
    String rpcId = "0";
    int pos = raw.lastIndexOf(':');
    if (pos > 0) {
      traceId = raw.substring(0, pos);
      int begin = pos + 1;
      if (begin < raw.length()) {
        rpcId = raw.substring(begin);
      }
    }

    request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
    return Pair.build(traceId, rpcId);
  }

  /**
   * 设定请求对象是否是爬虫
   *
   * @param request 请求对象
   * @param spider  是否是爬虫
   */
  public static void setSpider(HttpServletRequest request, Boolean spider) {
    request.setAttribute(IS_SPIDER_ATTRIBUTE, spider);
  }

  /**
   * 设定当前对象是需要染色的
   *
   * @param request   请求对象
   * @param colorized 是否需要染色
   */
  public static void setColorized(HttpServletRequest request, Boolean colorized) {
    request.setAttribute(IS_COLOR_ATTRIBUTE, colorized);
  }

  public static String getUserId(HttpServletRequest request) {
    return getAttribute(request, USER_ID_ATTRIBUTE);
  }

  public static void setUserId(HttpServletRequest request, String uid) {
    request.setAttribute(USER_ID_ATTRIBUTE, uid);
  }

  public static <T> T getAttribute(HttpServletRequest request, String name) {
    return getAttribute(request, name, null);
  }

  @SuppressWarnings("unchecked")
  public static <T> T getAttribute(HttpServletRequest request, String name, T defaultValue) {
    if (request == null)
      return defaultValue;
    Object obj = request.getAttribute(name);
    if (obj == null)
      return defaultValue;
    return (T) obj;
  }
}
