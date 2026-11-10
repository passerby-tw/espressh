package org.espressh.jbox;

import org.espressh.api.RuntimeSnapshotDto;

final class RuntimeView {
  static void display(RuntimeSnapshotDto snapshot, String current) {
    System.out.println(
        "Host JDK: "
            + snapshot.hostVersion()
            + " "
            + snapshot.hostJavaHome());
    System.out.println(
        "Espresso: " + snapshot.espressoVersion());
    System.out.println("ID\tVERSION\tVENDOR\tJAVA_HOME\tSELECTION");
    for (var row : snapshot.runtimes()) {
      String id = row.id();
      System.out.println(
          id
              + "\t"
              + row.version()
              + "\t"
              + row.vendor()
              + "\t"
              + row.javaHome()
              + "\t"
              + (id.equals(current) ? "[current]" : ""));
    }
  }
}
