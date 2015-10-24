package com.github.filter.io;

import com.google.common.base.Strings;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.*;
import java.nio.charset.Charset;
import java.util.zip.GZIPInputStream;

/**
 * 缓存响应信息，便于统计以及做response内容的修改
 * Created by lirui on 2014/7/17.
 */
public class BufferedResponseWrapper extends HttpServletResponseWrapper {
  private static final String NAME_ISO_8859_1 = "iso-8859-1";
  private static final String NAME_UTF_8 = "UTF-8";
  private final boolean GZipped;
  private Charset UTF8 = Charset.forName(NAME_UTF_8);
  private PrintWriter writer;
  private UnsafeByteArrayOutputStream out;
  private GZipStream zipStream;
  private int status = 200;
  private String location = null;
  private String errorMessage = null;
  private boolean error = false;

  /**
   * Constructs a response adaptor wrapping the given response.
   *
   * @param response 响应对象
   */
  public BufferedResponseWrapper(HttpServletResponse response) {
    this(response, false);
  }

  /**
   * Constructs a response adaptor wrapping the given response.
   *
   * @param response 响应对象
   */
  public BufferedResponseWrapper(HttpServletResponse response, boolean GZipped) {
    super(response);
    //设定一个较大的值，否则一旦header过多，就会出错
    response.setBufferSize(8192);
    this.GZipped = GZipped;
    out = new UnsafeByteArrayOutputStream(16 * 1024);
    if (GZipped) {
      try {
        zipStream = new GZipStream(out);
      } catch (IOException ignored) {
      }
    }
  }

  @Override
  public int getStatus() {
    return status;
  }

  @Override
  public void setStatus(int status) {
    this.status = status;
  }

  @Override
  public PrintWriter getWriter() throws IOException {
    if (writer == null) {
      writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), getEncodingCharset()));
    }
    return writer;
  }

  @Override
  public ServletOutputStream getOutputStream() throws IOException {
    return GZipped ? zipStream : out;
  }

  @Override
  public void flushBuffer() throws IOException {
    if (writer != null) {
      writer.flush();
    }
    if (GZipped) {
      zipStream.finish();
    }
    out.flush();
  }

  @Override
  public void reset() {
    status = 200;
    location = null;
    error = false;
    errorMessage = null;

    if (GZipped) {
      try {
        out.reset();
        zipStream = new GZipStream(out);
      } catch (IOException ignored) {
      }
    } else {
      out.reset();
    }
  }

  @Override
  public void sendError(int sc) throws IOException {
    trySendError(sc, null);
  }

  @Override
  public void sendError(int sc, String msg) throws IOException {
    trySendError(sc, msg);
  }

  private void trySendError(int sc, String msg) {
    status = sc;
    error = true;
    errorMessage = msg;
  }

  @Override
  public void sendRedirect(String location) throws IOException {
    status = 302;
    this.location = location;
  }

  /**
   * 返回响应的HTML内容
   */
  public String getContent() {
    try {
      flushBuffer();
    } catch (IOException ignored) {
    }
    if (GZipped) {
      ByteArrayOutputStream o = new ByteArrayOutputStream();
      try {
        GZIPInputStream in = new GZIPInputStream(out.asByteArrayInputStream());
        int i;
        while ((i = in.read()) != -1) {
          o.write(i);
        }
      } catch (IOException ignored) {
      }
      return new String(o.toByteArray(), getEncodingCharset());
    } else {
      return out.toString(getEncodingCharset());
    }
  }

  /**
   * 直接copy数据，避免string的转换
   *
   * @param o 输出流
   * @throws IOException
   */
  public void writeTo(OutputStream o) throws IOException {
    out.writeTo(o);
  }

  private Charset getEncodingCharset() {
    String encoding = getCharacterEncoding();
    if (Strings.isNullOrEmpty(encoding) || encoding.toLowerCase().equals(NAME_ISO_8859_1))
      return UTF8;
    try {
      return Charset.forName(encoding);
    } catch (Exception e) {
      return UTF8;
    }
  }

  @Override
  public void setCharacterEncoding(String charset) {
    if (charset != null && charset.toLowerCase().equals(NAME_ISO_8859_1))
      charset = NAME_UTF_8;
    super.setCharacterEncoding(charset);
  }

  @Override
  public void setContentType(String type) {
    if (type != null) {
      int pos = type.toLowerCase().lastIndexOf(NAME_ISO_8859_1);
      if (pos > 0) {
        String mime = type.substring(0, pos) + NAME_UTF_8;
        int start = pos + NAME_ISO_8859_1.length() + 1;
        if (start < type.length()) {
          mime += type.substring(start);
        }
        type = mime;
      }
    }
    super.setContentType(type);
  }

  /**
   * 返回响应的内容长度
   */
  public int getLength() {
    return out.size();
  }

  /**
   * 30x跳转的目标地址
   */
  public String getLocation() {
    return location;
  }

  /**
   * 是否有错误
   */
  public boolean isError() {
    return error;
  }

  /**
   * 是否做了GZIP压缩
   */
  public boolean isGZipped() {
    return GZipped;
  }

  /**
   * 错误信息
   */
  public String getErrorMessage() {
    return errorMessage;
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder("BufferedResponseWrapper{");
    sb.append("status=").append(status);
    sb.append(", error=").append(error);
    sb.append(", location='").append(location).append('\'');
    sb.append(", errorMessage='").append(errorMessage).append('\'');
    sb.append('}');
    return sb.toString();
  }
}
