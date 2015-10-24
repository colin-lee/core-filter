package com.github.filter.io;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;

/**
 * GZip压缩
 * Created by lirui on 2014/7/31.
 */
class GZipStream extends ServletOutputStream {

  private GZIPOutputStream zipStream;

  public GZipStream(OutputStream out) throws IOException {
    zipStream = new GZIPOutputStream(out, true);
  }

  @Override
  public void flush() throws IOException {
    zipStream.flush();
  }

  @Override
  public void write(byte[] bytes, int off, int len) throws IOException {
    zipStream.write(bytes, off, len);
  }

  @Override
  public void write(byte[] bytes) throws IOException {
    zipStream.write(bytes);
  }

  @Override
  public void write(int b) throws IOException {
    zipStream.write(b);
  }

  public void finish() throws IOException {
    zipStream.finish();
  }

  public void close() throws IOException {
    zipStream.close();
  }

  @Override
  public boolean isReady() {
    return true;
  }

  @Override
  public void setWriteListener(WriteListener writeListener) {
    throw new UnsupportedOperationException(getClass().getName() + " not support setWriteListener: " + writeListener);
  }
}
