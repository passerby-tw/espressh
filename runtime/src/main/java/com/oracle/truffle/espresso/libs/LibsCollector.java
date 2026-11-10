package com.oracle.truffle.espresso.libs;

/** SDK nine factories, then Context-owned empty awt and jaas libraries. */
public final class LibsCollector {
  public static <T> java.util.List<T> getInstances(Class<? extends T> clazz) {
    java.util.List<T> list = new java.util.ArrayList<>(11);
    list.add(clazz.cast(new com.oracle.truffle.espresso.libs.libjava.LibJava()));
    list.add(clazz.cast(new com.oracle.truffle.espresso.libs.libjimage.LibJimage()));
    list.add(clazz.cast(new com.oracle.truffle.espresso.libs.libjvm.LibJVM()));
    list.add(clazz.cast(new com.oracle.truffle.espresso.libs.libnio.LibNio()));
    list.add(clazz.cast(new com.oracle.truffle.espresso.libs.libnet.LibNet()));
    list.add(clazz.cast(new com.oracle.truffle.espresso.libs.libmanagement.LibManagement()));
    list.add(clazz.cast(new com.oracle.truffle.espresso.libs.libnespresso.LibNespresso()));
    list.add(clazz.cast(new com.oracle.truffle.espresso.libs.libmanagement_ext.LibManagementExt()));
    list.add(clazz.cast(new com.oracle.truffle.espresso.libs.libzip.LibZip()));
    list.add(clazz.cast(new org.espressh.runtime.substitutions.awt.EmptyAwtFactory()));
    list.add(clazz.cast(new org.espressh.runtime.substitutions.EmptyJaasFactory()));
    return list;
  }
}
