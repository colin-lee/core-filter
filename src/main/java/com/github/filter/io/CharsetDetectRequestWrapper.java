package com.github.filter.io;

import com.github.filter.CoreFilter;
import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在tomcat下，配置不好容易出乱码信息，这里统一处理一下编码解析。如果发现获取的String是乱码，就重新解析query串
 * Created by lirui on 2015/04/23 下午1:59.
 */
public class CharsetDetectRequestWrapper extends HttpServletRequestWrapper {
  public static final Charset GBK = Charset.forName("GBK");
  public static final Charset UTF8 = Charset.forName("UTF-8");
  private static final Logger log = LoggerFactory.getLogger(CoreFilter.class);
  private static final Pattern pattern = Pattern.compile("\\b(?:ie|enc|encoding)=(gbk|utf8|utf-8|gb2312|gb18030)\\b", Pattern.CASE_INSENSITIVE);
  private static final Map<String, Charset> chars = Maps.newHashMap();
  private static final int[] HEX2BYTE_CONVERT = new int[256];

  static {
    for (int i = 0; i < 256; i++) {
      if (i >= 'A' && i <= 'F') {
        HEX2BYTE_CONVERT[i] = i - 'A' + 10;
      } else if (i >= 'a' && i <= 'f') {
        HEX2BYTE_CONVERT[i] = i - 'a' + 10;
      } else if (i >= '0' && i <= '9') {
        HEX2BYTE_CONVERT[i] = i - '0';
      } else {
        HEX2BYTE_CONVERT[i] = 0;
      }
    }
  }

  private String encoding = null;

  /**
   * Constructs a request object wrapping the given request.
   *
   * @param request 请求对象
   * @throws IllegalArgumentException if the request is null
   */
  public CharsetDetectRequestWrapper(HttpServletRequest request) {
    super(request);
  }

  /**
   * 把一个byte的整数变成其对应的url编码格式，比如\n(10)会变成 %0A；
   * 这里使用了大写字母
   *
   * @param byteInt 单字节的byte
   * @return 代表一个byte的URLEncode字符串
   */
  private static String byte2hex(int byteInt) {
    StringBuilder sbd = new StringBuilder();
    sbd.append('%');
    char ch = Character.forDigit((byteInt >> 4) & 0xF, 16);
    appendUppercaseChar(sbd, ch);
    ch = Character.forDigit(byteInt & 0xF, 16);
    appendUppercaseChar(sbd, ch);
    return sbd.toString();
  }

  private static void appendUppercaseChar(StringBuilder sbd, char ch) {
    if (ch >= 'a' && ch <= 'z') {
      ch = (char) (ch - 32);
    }
    sbd.append(ch);
  }

  /**
   * 将一个URLEncode的hex表示的byte恢复
   *
   * @param hi 单字节高4bit的16进制
   * @param lo 单字节低4bit的16进制
   * @return 单字节的byte
   */
  private static int hex2byte(char hi, char lo) {
    return (HEX2BYTE_CONVERT[hi] << 4) + HEX2BYTE_CONVERT[lo];
  }

  @Override
  public String getParameter(String name) {
    if (encoding == null) {
      synchronized (this) {
        encoding = detectAndSetEncoding();
      }
    }
    //非UTF8编码需要特殊处理
    String val = super.getParameter(name);
    if (val != null && !encoding.startsWith("UTF")) {
      // 如果发现非ascii字符，则做解析原始串处理
      boolean found = false;
      for (int i = 0; i < val.length(); i++) {
        if (val.charAt(i) > 255) {
          found = true;
          break;
        }
      }
      if (found) {
        String raw = getRawQuery(name);
        try {
          val = URLDecoder.decode(raw, encoding);
        } catch (UnsupportedEncodingException e) {
          log.error("cannot decode '{}', enc={}", raw, encoding, e);
        }
      }
    }

    return val;
  }

  private String detectAndSetEncoding() {
    if (encoding == null) {
      String query = super.getQueryString(), enc = null;
      //支持通过参数指定编码信息
      if (query != null && query.length() > 5) {
        enc = detectByRequestParameter(query);
      }

      //通过URL编码识别是什么编码，对于UTF-8和GBK都是合法的编码组合，默认为UTF-8编码
      if (Strings.isNullOrEmpty(enc)) {
        enc = detectURLCharset(query);
      }
      if (Strings.isNullOrEmpty(enc)) {
        enc = "UTF-8";
      }

      try {
        super.setCharacterEncoding(enc);
        super.getParameter("_"); //必须先获取一下参数，否则设定的Encoding不生效
      } catch (UnsupportedEncodingException e) {
        log.error(query, e);
      }
      encoding = enc;
    }
    return encoding;
  }

  private String getRawQuery(String name) {
    String q = super.getQueryString();
    String prefix = name + "=";
    int pos = q.indexOf(prefix);
    if (pos == -1) {
      return "";
    }
    String raw = q.substring(pos + prefix.length());
    pos = raw.indexOf('&');
    if (pos > 0) {
      raw = raw.substring(0, pos);
    }
    return raw;
  }

  /**
   * 通过URL的特定参数名识别编码信息
   *
   * @param query URL的query串
   * @return 识别出来的字符编码，否则为null
   */
  private String detectByRequestParameter(String query) {
    Matcher m = pattern.matcher(query);
    Charset ch = null;
    if (m.find()) {
      String enc = m.group(1).toLowerCase();
      ch = chars.get(enc);
      if (ch == null) {
        try {
          ch = Charset.forName(enc);
          chars.put(enc, ch);
        } catch (Exception e) {
          log.error("FAIL\t{}\t{}", enc, query);
        }
      }
    }

    return ch == null ? null : ch.name();
  }

  /**
   * 检测url查询串的编码格式，仅处理UTF8,GBK两种
   *
   * @param query 检测的URL编码的查询串
   * @return 字符编码
   */
  private String detectURLCharset(String query) {
    try {
      isURLEncoded(query, UTF8);
      return "UTF-8";
    } catch (Exception e) {
      try {
        isURLEncoded(query, GBK);
      } catch (CharacterCodingException e1) {
        return "UTF-8";
      }
      return "GBK";
    }
  }

  /**
   * 检测字符串的编码方式，仅处理UTF8,GBK两种
   *
   * @param str 待检查的字符串
   * @return 字符编码
   */
  private String detectStringCharset(String str) {
    if (str == null) {
      return "UTF-8";
    }
    try {
      //超长的字符串，只获取其中非英文字符255个进行判定
      if (str.length() > 512) {
        StringBuilder sbd = new StringBuilder(255);
        for (int i = 0, j = 0, len = str.length(); i < len && j < 255; i++) {
          char c = str.charAt(i);
          if (c > '\u00ff') {
            sbd.append(c);
            j++;
          }
        }
        str = sbd.toString();
      }
      bytes2str(str2bytes(str, UTF8), UTF8);
      return "UTF-8";
    } catch (Exception e) {
      try {
        bytes2str(str2bytes(str, GBK), GBK);
      } catch (CharacterCodingException e1) {
        return "UTF-8";
      }
      return "GBK";
    }
  }

  /**
   * 遇见非法不可解析的字符就抛异常
   *
   * @param str     待检查的字符串
   * @param charset 待检查的字符编码
   * @return 如果是对应编码返回true，否则返回false
   */
  private boolean isURLEncoded(String str, Charset charset) throws CharacterCodingException {
    if (str == null || str.length() == 0)
      return true;
    //找到第一个百分号
    int pos = str.indexOf('%');
    if (pos > -1) {
      str = str.substring(pos);
    }
    int numChars = str.length();
    int trySize = numChars > 512 ? numChars / 2 : numChars;
    ByteArrayOutputStream bout = new ByteArrayOutputStream(trySize);
    for (int i = 0; i < numChars; i++) {
      char ch = str.charAt(i);
      if (ch == '%') {
        if (i + 2 >= numChars) {
          //url可能因为长度限制被截断，这里就不抛异常，避免误判
          return true;
        } else {
          char c1 = str.charAt(i + 1), c2 = str.charAt(i + 2);
          if (c1 > 255) {
            i++;
          } else if (c2 > 255) {
            i += 2;
          } else {
            bout.write(hex2byte(c1, c2));
            i += 2;
          }
        }
      } else if (ch > '\u00FF') {
        //非ascii字符直接不decode了
        bytes2str(bout.toByteArray(), charset);
        bout.reset();
      }
    }
    bytes2str(bout.toByteArray(), charset);
    return true;
  }

  private String bytes2str(byte[] bytes, Charset charset) throws CharacterCodingException {
    CharsetDecoder dec = charset.newDecoder();
    dec.onMalformedInput(CodingErrorAction.REPORT);
    dec.onUnmappableCharacter(CodingErrorAction.REPORT);
    CharBuffer cb = dec.decode(ByteBuffer.wrap(bytes));
    return cb.toString();
  }

  private byte[] str2bytes(String str, Charset charset) throws CharacterCodingException {
    CharsetEncoder enc = charset.newEncoder();
    enc.onMalformedInput(CodingErrorAction.REPORT);
    enc.onUnmappableCharacter(CodingErrorAction.REPORT);
    CharBuffer cb = CharBuffer.wrap(str.toCharArray());
    ByteBuffer bo = enc.encode(cb);
    return Arrays.copyOf(bo.array(), bo.limit());
  }
}
