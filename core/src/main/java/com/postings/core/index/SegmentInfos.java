package com.postings.core.index;

import com.postings.core.codec.CodecUtil;
import com.postings.core.codec.CorruptIndexException;
import com.postings.core.codec.VInt;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.CheckedOutputStream;

/**
 * Manages the commit point file (segments_N), tracking the list of active segments and deletion
 * generations.
 */
public final class SegmentInfos {

  private static final Pattern SEGMENTS_FILE_PATTERN = Pattern.compile("^segments_(\\d+)$");

  private long generation;
  private final List<SegmentCommitInfo> segments;

  public SegmentInfos(long generation, List<SegmentCommitInfo> segments) {
    this.generation = generation;
    this.segments = new ArrayList<>(segments);
  }

  public SegmentInfos() {
    this(0, new ArrayList<>());
  }

  public long generation() {
    return generation;
  }

  public List<SegmentCommitInfo> segments() {
    return Collections.unmodifiableList(segments);
  }

  public void add(SegmentCommitInfo info) {
    segments.add(info);
  }

  public void setSegments(List<SegmentCommitInfo> newSegments) {
    segments.clear();
    segments.addAll(newSegments);
  }

  public int size() {
    return segments.size();
  }

  public SegmentCommitInfo get(int index) {
    return segments.get(index);
  }

  public long totalLiveDocs() {
    long count = 0;
    for (SegmentCommitInfo info : segments) {
      count += (info.docCount() - info.delCount());
    }
    return count;
  }

  /** Atomically commits this SegmentInfos to disk as segments_N. */
  public synchronized void commit(Path dir) throws IOException {
    long nextGen = generation + 1;
    String fileName = "segments_" + nextGen;
    Path targetFile = dir.resolve(fileName);
    Path tempFile = dir.resolve(fileName + ".tmp");

    CRC32 crc = new CRC32();
    try (BufferedOutputStream bos = new BufferedOutputStream(Files.newOutputStream(tempFile));
        CheckedOutputStream cos = new CheckedOutputStream(bos, crc);
        DataOutputStream out = new DataOutputStream(cos)) {

      CodecUtil.writeHeader(out, CodecUtil.VERSION_CURRENT);
      out.writeLong(nextGen);
      out.writeInt(segments.size());

      for (SegmentCommitInfo info : segments) {
        byte[] nameBytes = info.segmentName().getBytes(StandardCharsets.UTF_8);
        VInt.writeVInt(out, nameBytes.length);
        out.write(nameBytes);
        out.writeInt(info.docCount());
        out.writeInt(info.delGen());
        out.writeInt(info.delCount());
      }

      CodecUtil.writeFooter(out, crc);
    }

    // Fsync the temp file before rename
    fsync(tempFile);

    // Atomic move
    Files.move(
        tempFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

    // Fsync the directory
    fsyncDir(dir);

    this.generation = nextGen;
  }

  /**
   * Reads the latest valid commit point (highest valid segments_N) from the directory. Returns
   * empty SegmentInfos if no commit exists.
   */
  public static SegmentInfos readLatest(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return new SegmentInfos(0, List.of());
    }

    List<Long> generations = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "segments_*")) {
      for (Path p : stream) {
        String name = p.getFileName().toString();
        Matcher matcher = SEGMENTS_FILE_PATTERN.matcher(name);
        if (matcher.matches()) {
          generations.add(Long.parseLong(matcher.group(1)));
        }
      }
    }

    generations.sort(Collections.reverseOrder());

    for (long gen : generations) {
      Path segFile = dir.resolve("segments_" + gen);
      try {
        return read(segFile, gen);
      } catch (IOException e) {
        // Skip corrupt or incomplete commit file (e.g. from crash)
      }
    }

    return new SegmentInfos(0, List.of());
  }

  public static SegmentInfos read(Path file, long expectedGen) throws IOException {
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      CodecUtil.verifyFooter(channel);
    }

    try (DataInputStream in =
        new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
      CodecUtil.checkHeader(in);
      long gen = in.readLong();
      if (gen != expectedGen) {
        throw new CorruptIndexException("SegmentInfos gen mismatch: " + gen + " vs " + expectedGen);
      }
      int numSegments = in.readInt();
      List<SegmentCommitInfo> list = new ArrayList<>(numSegments);

      for (int i = 0; i < numSegments; i++) {
        int nameLen = VInt.readVInt(in);
        byte[] nameBytes = new byte[nameLen];
        in.readFully(nameBytes);
        String name = new String(nameBytes, StandardCharsets.UTF_8);
        int docCount = in.readInt();
        int delGen = in.readInt();
        int delCount = in.readInt();
        list.add(new SegmentCommitInfo(name, docCount, delGen, delCount));
      }

      return new SegmentInfos(gen, list);
    }
  }

  private static void fsync(Path file) throws IOException {
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      channel.force(true);
    }
  }

  private static void fsyncDir(Path dir) {
    try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
      channel.force(true);
    } catch (Exception ignored) {
      // Directory fsync is not supported on all operating systems (e.g. Windows)
    }
  }
}
