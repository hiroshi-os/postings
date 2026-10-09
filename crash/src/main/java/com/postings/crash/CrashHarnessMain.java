package com.postings.crash;

/** Crash harness entry point. */
public final class CrashHarnessMain {
  private CrashHarnessMain() {}

  public static void main(String[] args) {
    System.out.println("Crash harness running with args: " + String.join(" ", args));
  }
}
