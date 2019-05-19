package com.faforever.client.map.generator;

import com.faforever.client.io.FileUtils;
import com.faforever.client.preferences.PreferencesService;
import com.faforever.client.task.TaskService;
import com.google.common.annotations.VisibleForTesting;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.nocatch.NoCatch.noCatch;

@Lazy
@Service
@Slf4j
public class MapGeneratorService {

  // The server expects lower case names
  static final String GENERATED_MAP_NAME = "neroxis_map_generator_%s_%d";
  private static final String GENERATOR_DEFAULT_VERSION = "0.1.1";
  static final String GENERATOR_EXECUTABLE_FILENAME = "MapGenerator_%s.jar";
  static final String GENERATOR_EXECUTABLE_SUB_DIRECTORY = "map_generator";
  private static final Pattern VERSION_PATTERN = Pattern.compile("\\d\\d?\\d?\\.\\d\\d?\\d?\\.\\d\\d?\\d?");
  private static final Pattern GENERATED_MAP_PATTERN = Pattern.compile("neroxis_map_generator_(" + VERSION_PATTERN + ")_(-?\\d+)");

  static final int GENERATION_TIMEOUT_SECONDS = 60;

  @Getter
  private final Path generatorExecutableDir;
  private final ApplicationContext applicationContext;
  private final TaskService taskService;

  @Getter
  private Path customMapsDirectory;
  private Random seedGenerator;

  public MapGeneratorService(
    ApplicationContext applicationContext,
    PreferencesService preferencesService,
    TaskService taskService
  ) {
    this.applicationContext = applicationContext;
    this.taskService = taskService;

    generatorExecutableDir = preferencesService.getFafDataDirectory().resolve(GENERATOR_EXECUTABLE_SUB_DIRECTORY);
    if (!Files.exists(generatorExecutableDir)) {
      try {
        Files.createDirectory(generatorExecutableDir);
      } catch (IOException e) {
        log.error("Could not create map generator executable directory.", e);
      }
    }

    seedGenerator = new Random();
    customMapsDirectory = preferencesService.getPreferences().getForgedAlliance().getCustomMapsDirectory();
  }

  @PostConstruct
  public void postConstruct() {
    deleteGeneratedMaps();
  }

  private void deleteGeneratedMaps() {
    log.info("Deleting leftover generated maps...");

    if (customMapsDirectory != null && customMapsDirectory.toFile().exists()) {
      noCatch(() -> Files.list(customMapsDirectory))
        .filter(Files::isDirectory)
        .filter(f -> GENERATED_MAP_PATTERN.matcher(f.getFileName().toString()).matches())
        .forEach(f -> noCatch(() -> FileUtils.deleteRecursively(f)));
    }
  }

  public CompletableFuture<String> generateMap() {
    return generateMap(GENERATOR_DEFAULT_VERSION, seedGenerator.nextLong());
  }

  public CompletableFuture<String> generateMap(String mapName) {
    Matcher matcher = GENERATED_MAP_PATTERN.matcher(mapName);
    if (!matcher.find()) {
      throw new IllegalArgumentException(String.format("Doesn't match pattern '%s': %s", GENERATED_MAP_PATTERN, mapName));
    }
    return generateMap(matcher.group(1), Long.parseLong(matcher.group(2)));
  }

  @VisibleForTesting
  CompletableFuture<String> generateMap(String version, long seed) {
    String generatorExecutableFileName = String.format(GENERATOR_EXECUTABLE_FILENAME, version);
    File generatorExecutableFile = generatorExecutableDir.resolve(generatorExecutableFileName).toFile();

    CompletableFuture<Void> downloadGeneratorFuture;
    if (!generatorExecutableFile.exists()) {
      if (!VERSION_PATTERN.matcher(version).matches()) {
        log.error("Unsupported generator version: {}", version);
        return CompletableFuture.supplyAsync(() -> {
          throw new RuntimeException("Unsupported generator version: " + version);
        });
      }

      log.info("Downloading MapGenerator version: {}", version);
      DownloadMapGeneratorTask downloadMapGeneratorTask = applicationContext.getBean(DownloadMapGeneratorTask.class);
      downloadMapGeneratorTask.setVersion(version);
      downloadGeneratorFuture = taskService.submitTask(downloadMapGeneratorTask).getFuture();
    } else {
      log.info("Found MapGenerator version: {}", version);
      downloadGeneratorFuture = CompletableFuture.completedFuture(null);
    }

    String mapFilename = String.format(MapGeneratorService.GENERATED_MAP_NAME, version, seed);

    GenerateMapTask generateMapTask = applicationContext.getBean(GenerateMapTask.class);
    generateMapTask.setVersion(version);
    generateMapTask.setSeed(seed);
    generateMapTask.setGeneratorExecutableFile(generatorExecutableFile);
    generateMapTask.setMapFilename(mapFilename);

    return downloadGeneratorFuture.thenApplyAsync((aVoid) -> {
      CompletableFuture<Void> generateMapFuture = taskService.submitTask(generateMapTask).getFuture();
      generateMapFuture.join();
      return mapFilename;
    });
  }


  public boolean isGeneratedMap(String mapName) {
    return GENERATED_MAP_PATTERN.matcher(mapName).matches();
  }
}