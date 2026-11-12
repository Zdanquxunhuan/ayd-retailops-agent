package com.yu.retailops.api;

import com.yu.retailops.application.TaskService;
import com.yu.retailops.domain.Diagnosis.*;
import com.yu.retailops.tool.FixtureCatalog;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class TaskController {
    private final TaskService tasks;
    private final FixtureCatalog fixtures;

    public TaskController(TaskService tasks, FixtureCatalog fixtures) {
        this.tasks = tasks;
        this.fixtures = fixtures;
    }

    @PostMapping("/tasks")
    public ResponseEntity<Task> create(@RequestBody Input input) {
        Task task = tasks.create(input);
        return ResponseEntity.accepted().header("Location", "/api/tasks/" + task.id).body(task);
    }

    @GetMapping("/tasks/{id}")
    public Task get(@PathVariable String id) {
        return tasks.get(id);
    }

    @GetMapping("/tasks/{id}/events")
    public List<Event> events(@PathVariable String id, @RequestParam(defaultValue = "0") long afterSequence) {
        return tasks.get(id).events.stream().filter(e -> e.sequence() > afterSequence).toList();
    }

    @GetMapping("/tasks/{id}/investigation")
    public ResponseEntity<com.yu.retailops.domain.Investigation.Trace> investigation(@PathVariable String id) {
        var trace = tasks.get(id).investigation;
        return trace == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(trace);
    }

    @GetMapping("/tasks/{id}/report")
    public ResponseEntity<Report> report(@PathVariable String id) {
        Report report = tasks.get(id).report;
        return report == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(report);
    }

    public record Clarification(String storeId, String orderId, long expectedVersion) {
    }

    @PostMapping("/tasks/{id}/clarifications")
    public Task clarify(@PathVariable String id, @RequestBody Clarification input) {
        return tasks.clarify(id, input.storeId(), input.orderId(), input.expectedVersion());
    }

    @PostMapping("/tasks/{id}/cancel")
    public ResponseEntity<Task> cancel(@PathVariable String id) {
        return ResponseEntity.accepted().body(tasks.cancel(id));
    }

    @GetMapping("/fixtures")
    public List<Map<String, String>> fixtures() {
        return fixtures.all().stream().map(f -> Map.of("id", f.id(), "description", f.description(), "dataSource", "STUB")).toList();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest() {
        return Map.of("error", "INVALID_REQUEST");
    }

    @ExceptionHandler(SecurityException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, String> forbidden() {
        return Map.of("error", "STORE_DENIED");
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> missing() {
        return Map.of("error", "TASK_NOT_FOUND");
    }

    @ExceptionHandler(TaskService.ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict() {
        return Map.of("error", "VERSION_OR_STATE_CONFLICT");
    }
}
