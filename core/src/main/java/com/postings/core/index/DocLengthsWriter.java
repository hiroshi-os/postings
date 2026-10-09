package com.postings.core.index;

import com.postings.core.codec.CodecUtil;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;
import java.util.zip.CheckedOutputStream;

/** Reads and writes per-document lengths (.len file). */
public final class DocLengthsWriter {

  private DocLengthsWriter() {}

  public static void write(Path file, int[] docLengths) throws IOException {
    CRC32 crc = new CRC32();
    try (BufferedOutputStream bos = new BufferedOutputStream(Files.newOutputStream(file));
        CheckedOutputStream cos = new CheckedOutputStream(bos, crc);
        DataOutputStream out = new DataOutputStream(cos)) {

      CodecUtil.writeHeader(out, CodecUtil.VERSION_CURRENT);
      out.writeInt(docLengths.length);
      for (int len : docLengths) {
        out.writeInt(len);
      }
      CodecUtil.writeFooter(out, crc);
    }
  }

  public static int[] read(Path file) throws IOException {
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      CodecUtil.verifyFooter(channel);
    }

    try (DataInputStream in =
        new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
      CodecUtil.checkHeader(in);
      int numDocs = in.readInt();
      int[] lengths = new int[numDocs];
      for (int i = 0; i < numDocs; i++) {
        lengths[i] = in.readInt();
      }
      return lengths;
    }
  }
}
