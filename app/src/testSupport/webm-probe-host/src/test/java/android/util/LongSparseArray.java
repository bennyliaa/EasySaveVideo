package android.util;

import java.util.ArrayList;
import java.util.TreeMap;

/** Original host-test shim; no Android framework/runtime behavior is asserted. */
public class LongSparseArray<E> {
  private final TreeMap<Long, E> values = new TreeMap<>();
  public LongSparseArray() {}
  public LongSparseArray(int capacity) {}
  public E get(long key) { return values.get(key); }
  public E get(long key, E fallback) { return values.getOrDefault(key, fallback); }
  public void put(long key, E value) { values.put(key, value); }
  public void append(long key, E value) { put(key, value); }
  public void clear() { values.clear(); }
  public int size() { return values.size(); }
  public long keyAt(int index) { return new ArrayList<>(values.keySet()).get(index); }
  public E valueAt(int index) { return values.get(keyAt(index)); }
  public void delete(long key) { values.remove(key); }
  public void remove(long key) { values.remove(key); }
}
