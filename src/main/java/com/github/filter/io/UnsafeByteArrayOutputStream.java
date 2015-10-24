/*
 * Copyright 1999-2011 Alibaba Group.
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.filter.io;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

/**
 * UnsafeByteArrayOutputStream.
 *
 * @author qian.lei
 */

class UnsafeByteArrayOutputStream extends ServletOutputStream {
  protected byte mBuffer[];

  protected int mCount = 0;

  public UnsafeByteArrayOutputStream() {
    this(16 * 1024);
  }

  public UnsafeByteArrayOutputStream(int size) {
    if (size < 0)
      throw new IllegalArgumentException("Negative initial size: " + size);
    mBuffer = new byte[size];
  }

  /**
   * byte array copy.
   *
   * @param src    src.
   * @param length new length.
   * @return new byte array.
   */
  public static byte[] copyOf(byte[] src, int length) {
    byte[] dst = new byte[length];
    System.arraycopy(src, 0, dst, 0, Math.min(src.length, length));
    return dst;
  }

  public void write(int b) {
    int newCount = mCount + 1;
    if (newCount > mBuffer.length) {
      mBuffer = copyOf(mBuffer, Math.max(mBuffer.length << 1, newCount));
    }
    mBuffer[mCount] = (byte) b;
    mCount = newCount;
  }

  public void write(byte b[], int off, int len) {
    if ((off < 0) || (off > b.length) || (len < 0) || ((off + len) > b.length) || ((off + len) < 0))
      throw new IndexOutOfBoundsException();
    if (len == 0)
      return;
    int newCount = mCount + len;
    if (newCount > mBuffer.length)
      mBuffer = copyOf(mBuffer, Math.max(mBuffer.length << 1, newCount));
    System.arraycopy(b, off, mBuffer, mCount, len);
    mCount = newCount;
  }

  public int size() {
    return mCount;
  }

  public void reset() {
    mCount = 0;
  }

  public ByteArrayInputStream asByteArrayInputStream() {
    return new ByteArrayInputStream(mBuffer, 0, mCount);
  }

  public void writeTo(OutputStream out) throws IOException {
    out.write(mBuffer, 0, mCount);
  }

  public String toString() {
    return new String(mBuffer, 0, mCount);
  }

  public String toString(String charset) throws UnsupportedEncodingException {
    return new String(mBuffer, 0, mCount, charset);
  }

  public String toString(Charset charset) {
    return new String(mBuffer, 0, mCount, charset);
  }

  public void close() throws IOException {
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
