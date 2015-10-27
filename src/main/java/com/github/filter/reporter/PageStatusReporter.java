package com.github.filter.reporter;

import com.alibaba.fastjson.JSON;
import com.alibaba.rocketmq.common.message.Message;
import com.github.autoconf.helper.ConfigHelper;
import com.github.trace.NamedThreadFactory;
import com.github.trace.bean.URIBean;
import com.github.trace.sender.RocketMqSender;
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.regex.Pattern;

/**
 * 定期上报页面状态信息
 * Created by lirui on 2015-10-27 12:02.
 */
public class PageStatusReporter implements Runnable {
  private static final Logger LOG = LoggerFactory.getLogger(PageStatusReporter.class);
  private static final PageStatusReporter INSTANCE = new PageStatusReporter();
  private static final Pattern NUM_PATTERN = Pattern.compile("[0-9]{2,}");
  private static final Pattern MD5_PATTERN = Pattern.compile("[0-9a-fA-F]{32}");
  private final int pvLimit = 10;
  private ScheduledExecutorService executor;
  private ConcurrentMap<String, AtomicIntegerArray> counters = Maps.newConcurrentMap();

  private PageStatusReporter() {
    NamedThreadFactory factory = new NamedThreadFactory("page-status-reporter", true);
    executor = Executors.newSingleThreadScheduledExecutor(factory);
    executor.schedule(this, 1, TimeUnit.MINUTES);
    Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
      @Override
      public void run() {
        executor.shutdown();
      }
    }));
  }

  public static PageStatusReporter getInstance() {
    return INSTANCE;
  }

  public void stat(HttpServletRequest request, long cost, int status, boolean isSpider) {
    String uri = getFormalURI(request);
    AtomicIntegerArray cnt = counters.get(uri);
    if (cnt == null) {
      cnt = new AtomicIntegerArray(8);
      AtomicIntegerArray old = counters.putIfAbsent(uri, cnt);
      if (old != null) {
        cnt = old;
      }
    }
    cnt.incrementAndGet(0); //totalPv;
    if (isSpider) {
      cnt.incrementAndGet(1); //spiderPv
    }
    if (status >= 400 && !isSpider) {
      // 只统计非爬虫的失败PV，避免问题页已删除问题，爬虫抓取的时候导致很多告警
      cnt.incrementAndGet(2); //failPv
    }
    cnt.addAndGet(3, (int) cost); //totalCost
    //新增针对状态码的细分统计
    if (status >= 500) {
      cnt.incrementAndGet(4);
    } else if (status >= 400) {
      cnt.incrementAndGet(5);
    } else if (status >= 300) {
      cnt.incrementAndGet(6);
    } else {
      cnt.incrementAndGet(7);
    }
  }

  /**
   * 构造归一化的uri，避免uri过度分散导致数据库行数过多
   *
   * @param request 请求对象
   * @return 归一化的URI
   */
  private String getFormalURI(HttpServletRequest request) {
    String uri = request.getRequestURI();
    if (uri == null || uri.length() == 0 || uri.equals("/")) {
      return "/";
    } else {
      //部分请求用';'分割，后面是请求参数
      int pos = uri.indexOf(';');
      if (pos != -1) {
        uri = uri.substring(0, pos);
      }
      //百科的部分页面用了MD5作为.htm的前缀
      String s = MD5_PATTERN.matcher(uri).replaceFirst("*");
      if (s.length() != uri.length()) {
        return s;
      }
      //问问和百科的部分页面都用了一些数字id作为.htm的前缀
      return NUM_PATTERN.matcher(uri).replaceAll("*");
    }
  }

  @Override
  public void run() {
    if (counters.size() <= 0) {
      return;
    }
    final Map<String, AtomicIntegerArray> old = counters;
    counters = Maps.newConcurrentMap();
    String name = ConfigHelper.getProcessInfo().getName();
    String serverIp = ConfigHelper.getServerInnerIP();
    for (Map.Entry<String, AtomicIntegerArray> kv : old.entrySet()) {
      AtomicIntegerArray cnt = kv.getValue();
      URIBean.Builder builder = new URIBean.Builder();
      int totalPv = cnt.get(0);
      //只有总PV超过一定次数才上报，避免一些乱七八糟的URL上报
      if (totalPv <= pvLimit) {
        LOG.warn("skip uri={}, pv={}", kv.getKey(), totalPv);
        continue;
      }
      builder.app(name).uri(kv.getKey()).totalPv(totalPv).spiderPv(cnt.get(1)).failPv(cnt.get(2)).totalCost(cnt.get(3)).pv50x(cnt.get(4)).pv40x(cnt.get(5)).pv30x(cnt.get(6)).pv20x(cnt.get(7));
      URIBean bean = builder.build();
      bean.setServerIp(serverIp);
      RocketMqSender.getInstance().asyncSend(new Message("JinJingPage", "", JSON.toJSONBytes(bean)));
    }
    if (old.size() > 0) {
      LOG.warn("send {} URIBean to RocketMQ", old.size());
    }
  }
}
