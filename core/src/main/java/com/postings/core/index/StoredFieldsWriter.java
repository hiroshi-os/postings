package com.postings.core.index;

import com.postings.core.codec.CodecUtil;
import com.postings.core.codec.CorruptIndexException;
import com.postings.core.model.Document;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.CheckedOutputStream;

/** Reads and writes stored document fields (id and title) (.stored file). */
public final class StoredFieldsWriter {

  private StoredFieldsWriter() {}

  public static void write(Path file, List<String> docIds, List<String> docTitles)
      throws IOException {
    CRC32 crc = new CRC32();
    int numDocs = docIds.size();

    // Prepare payload in memory or temp stream
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutputStream payloadOut = new DataOutputStream(baos);
    long[] offsets = new long[numDocs + 1];

    for (int i = 0; i < numDocs; i++) {
      offsets[i] = baos.size();
      byte[] idBytes = docIds.get(i).getBytes(StandardCharsets.UTF_8);
      byte[] titleBytes = docTitles.get(i).getBytes(StandardCharsets.UTF_8);
      payloadOut.writeInt(idBytes.length);
      payloadOut.write(idBytes);
      payloadOut.writeInt(titleBytes.length);
      payloadOut.write(titleBytes);
    }
    payloadOut.flush();
    offsets[numDocs] = baos.size();
    byte[] payloadBytes = baos.toByteArray();

    try (BufferedOutputStream bos = new BufferedOutputStream(Files.newOutputStream(file));
        CheckedOutputStream cos = new CheckedOutputStream(bos, crc);
        DataOutputStream out = new DataOutputStream(cos)) {

      CodecUtil.writeHeader(out, CodecUtil.VERSION_CURRENT);
      out.writeInt(numDocs);

      // Write offsets table
      for (long offset : offsets) {
        out.writeLong(offset);
      }

      // Write payload
      out.write(payloadBytes);

      CodecUtil.writeFooter(out, crc);
    }
  }

  public static final class Reader implements AutoCloseable {
    private final FileChannel channel;
    private final int numDocs;
    private final long[] offsets;
    private final long payloadStartOffset;

    public Reader(Path file) throws IOException {
      this.channel = FileChannel.open(file, StandardOpenOption.READ);
      CodecUtil.verifyFooter(channel);

      ByteBuffer headerBuf = ByteBuffer.allocate(12); // magic (4), version (4), numDocs (4)
      channel.read(headerBuf, 0);
      headerBuf.flip();

      int magic = headerBuf.getInt();
      if (magic != CodecUtil.CODEC_MAGIC) {
        throw new CorruptIndexException("Invalid codec magic: " + magic);
      }
      int version = headerBuf.getInt();
      if (version != CodecUtil.VERSION_CURRENT) {
        throw new CorruptIndexException("Unsupported codec version: " + version);
      }
      this.numDocs = headerBuf.getInt();

      this.offsets = new long[numDocs + 1];
      int offsetsByteSize = (numDocs + 1) * 8;
      ByteBuffer offsetsBuf = ByteBuffer.allocate(offsetsByteSize);
      channel.read(offsetsBuf, 12);
      offsetsBuf.flip();
      for (int i = 0; i <= numDocs; i++) {
        offsets[i] = offsetsBuf.getLong();
      }

      this.payloadStartOffset = 12L + offsetsByteSize;
    }

    public int numDocs() {
      return numDocs;
    }

    public Document get(int docId) throws IOException {
      if (docId < 0 || docId >= numDocs) {
        throw new IndexOutOfBoundsException("docId " + docId + " out of bounds");
      }
      long start = payloadStartOffset + offsets[docId];
      int len = (int) (offsets[docId + 1] - offsets[docId]);

      ByteBuffer docBuf = ByteBuffer.allocate(len);
      channel.read(docBuf, start);
      docBuf.flip();

      int idLen = docBuf.getInt();
      byte[] idBytes = new byte[idLen];
      docBuf.get(idBytes);
      String id = new String(idBytes, StandardCharsets.UTF_8);

      int titleLen = docBuf.getInt();
      byte[] titleBytes = new byte[titleLen];
      docBuf.get(titleBytes);
      String title = new String(titleBytes, StandardCharsets.UTF_8);

      return new Document(id, title, "");
    }

    @Override
    public void close() throws IOException {
      channel.close();
    }
  }
}
