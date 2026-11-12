package com.yu.retailops.persistence;

import com.yu.retailops.domain.Diagnosis.Task;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** Local single-process persistence. Atomic snapshots; intentionally not a distributed production store. */
public class FileTaskRepository implements AutoCloseable {
    private final Path root;
    private final JsonMapper json;
    private final FileChannel lockChannel;
    private final FileLock lock;
    public FileTaskRepository(Path root, JsonMapper json) throws IOException {
        this.root = root.toAbsolutePath().normalize(); this.json = json;
        Files.createDirectories(this.root);
        lockChannel = FileChannel.open(this.root.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lock = lockChannel.tryLock();
        if (lock == null) { lockChannel.close(); throw new IOException("Task directory is already in use"); }
    }
    private Path path(String id) { return root.resolve(UUID.fromString(id) + ".json"); }
    public synchronized void save(Task task) {
        Path temp = root.resolve(task.id + ".tmp");
        try {
            byte[] bytes = json.writeValueAsBytes(task);
            // Shutdown/cancellation may interrupt the worker before its final checkpoint.
            // FileChannel.write closes on that interrupt, losing the stopped state; FileOutputStream
            // lets this bounded local snapshot finish while preserving the thread's interrupt flag.
            try (var stream = new FileOutputStream(temp.toFile())) {
                stream.write(bytes);
                stream.getFD().sync();
            }
            Files.move(temp, path(task.id), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { throw new UncheckedIOException("Task persistence failed", e); }
    }
    public synchronized Task get(String id) {
        try {
            Path p = path(id);
            if (!Files.exists(p)) throw new NoSuchElementException("Task not found");
            return json.readValue(Files.readAllBytes(p), Task.class);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    public synchronized List<Task> all() {
        try (var files = Files.list(root)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(p -> get(p.getFileName().toString().replace(".json", ""))).toList();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    public void close() throws IOException { lock.release(); lockChannel.close(); }
}
