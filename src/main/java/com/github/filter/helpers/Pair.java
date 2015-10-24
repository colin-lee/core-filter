package com.github.filter.helpers;

/**
 * A simple object that holds onto a pair of object references, first and second.
 */
final public class Pair<FIRST, SECOND> {
  public final FIRST first;
  public final SECOND second;

  public Pair(FIRST first, SECOND second) {
    this.first = first;
    this.second = second;
  }

  private static boolean equal(Object a, Object b) {
    return (a == b) || (a != null && a.equals(b));
  }

  /**
   * 通过值创建值对
   *
   * @param f 第一个值
   * @param s 第二个值
   * @return 值对
   */
  public static <FIRST, SECOND> Pair<FIRST, SECOND> build(FIRST f, SECOND s) {
    return new Pair<>(f, s);
  }

  public static <FIRST, SECOND> Pair<FIRST, SECOND> makePair(FIRST f, SECOND s) {
    return build(f, s);
  }

  @Override
  public int hashCode() {
    return 17 * ((first != null) ? first.hashCode() : 0) + 17 * ((second != null) ? second.hashCode() : 0);
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof Pair<?, ?>)) {
      return false;
    }
    Pair<?, ?> that = (Pair<?, ?>) o;
    return equal(this.first, that.first) && equal(this.second, that.second);
  }

  @Override
  public String toString() {
    return String.format("(%s,%s)", first, second);
  }
}
