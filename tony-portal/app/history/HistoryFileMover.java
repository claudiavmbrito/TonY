package history;

import cache.CacheWrapper;
import com.linkedin.tony.Constants;
import com.linkedin.tony.TonyConfigurationKeys;
import com.linkedin.tony.util.ParserUtils;
import com.linkedin.tony.util.Utils;
import com.typesafe.config.Config;
import hadoop.Requirements;
import java.io.IOException;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import play.Logger;
import utils.ConfigUtils;


@Singleton
public class HistoryFileMover {
  private static final Logger.ALogger LOG = Logger.of(HistoryFileMover.class);

  private final FileSystem fs;
  private final Path intermediateDir;
  private final Path finishedDir;
  private final CacheWrapper cacheWrapper;

  @Inject
  public HistoryFileMover(Config appConf, Requirements requirements, CacheWrapper cacheWrapper) {
    fs = requirements.getFileSystem();
    intermediateDir = requirements.getIntermediateDir();
    finishedDir = requirements.getFinishedDir();
    this.cacheWrapper = cacheWrapper;

    ScheduledExecutorService scheduledThreadPool = Executors.newScheduledThreadPool(1);
    long moverIntervalMs = ConfigUtils.fetchIntConfigIfExists(appConf,
        TonyConfigurationKeys.TONY_HISTORY_MOVER_INTERVAL_MS,
        TonyConfigurationKeys.DEFAULT_TONY_HISTORY_MOVER_INTERVAL_MS);

    LOG.info("Starting background history file mover thread, will run every " + moverIntervalMs + " milliseconds.");
    scheduledThreadPool.scheduleAtFixedRate(() -> {
      FileStatus[] jobDirs = null;
      try {
        jobDirs = fs.listStatus(intermediateDir);
      } catch (IOException e) {
        LOG.error("Failed to list files in " + intermediateDir, e);
      }
      if (jobDirs != null) {
        try {
          moveIntermediateToFinished(fs, jobDirs);
        } catch (Exception e) {
          LOG.error("Encountered exception while moving history directories", e);
        }
      }
    }, 0, moverIntervalMs, TimeUnit.MILLISECONDS);
  }

  private void moveIntermediateToFinished(FileSystem fs, FileStatus[] jobDirs) {
    for (FileStatus jobDir : jobDirs) {
      cacheWrapper.updateCaches(jobDir.getPath());
      String jhistFilePath = ParserUtils.getJhistFilePath(fs, jobDir.getPath());
      if (jhistFilePath == null || jobInProgress(jhistFilePath)) {
        continue;
      }

      Path source = new Path(jhistFilePath).getParent();
      StringBuilder path = new StringBuilder(finishedDir.toString());
      Date endDate = new Date(ParserUtils.getCompletedTimeFromJhistFileName(jhistFilePath));
      path.append(Path.SEPARATOR).append(ParserUtils.getYearMonthDayDirectory(endDate));
      if (fs.getScheme().equals("file")) {
        // On Mac, local filesystem will copy contents of source dir to dest dir, so we have to append the dir name
        // to compensate.
        path.append(Path.SEPARATOR).append(source.getName());
      }
      Utils.createDirIfNotExists(fs, new Path(path.toString()), Constants.PERM770);

      Path dest = new Path(path.toString());
      LOG.info("Moving " + source + " to " + dest);
      try {
        fs.rename(source, dest);
      } catch (IOException e) {
        LOG.error("Failed to move files from intermediate to finished", e);
      }
    }
  }

  private boolean jobInProgress(String jhistFileName) {
    return !jhistFileName.endsWith(Constants.HISTFILE_SUFFIX);
  }
}
