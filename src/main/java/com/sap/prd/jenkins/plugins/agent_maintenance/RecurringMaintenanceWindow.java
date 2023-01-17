package com.sap.prd.jenkins.plugins.agent_maintenance;

import static hudson.Util.fixNull;

import antlr.ANTLRException;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.scheduler.CronTabList;
import hudson.security.ACL;
import hudson.util.FormValidation;
import java.io.InvalidObjectException;
import java.io.ObjectStreamException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;
import org.springframework.security.core.Authentication;

/**
 * Defines a recurring maintenance window based on a cron like schedule.
 */
public class RecurringMaintenanceWindow extends AbstractDescribableImpl<RecurringMaintenanceWindow> {

  /*
   * The interval between 2 check runs in minutes.
   */
  @SuppressFBWarnings("MS_SHOULD_BE_FINAL") // Used to set the check interval
  @Restricted(NoExternalUse.class)
  public static int CHECK_INTERVAL_MINUTES = Integer.getInteger(RecurringMaintenanceWindow.class.getName() + ".CHECK_INTERVAL_MINUTES", 15);

  /*
   * The amount of time a maintenance window is created in advance in days
   */
  @SuppressFBWarnings("MS_SHOULD_BE_FINAL") // Used to control usage of binary or shell wrapper
  @Restricted(NoExternalUse.class)
  public static int LEAD_TIME_DAYS = Integer.getInteger(RecurringMaintenanceWindow.class.getName() + ".LEAD_TIME_DAYS", 7);

  private static final Logger LOGGER = Logger.getLogger(RecurringMaintenanceWindow.class.getName());
  private final String reason;
  private final boolean takeOnline;
  private final boolean keepUpWhenActive;
  private final String maxWaitMinutes;
  private final String userid;
  private String id;
  private final String startTimeSpec;
  private final int duration;
  private long nextCheck = 0;
  private transient CronTabList tabs;

  /**
   * Creates a new recurring maintenance window.
   *
   * @param startTimeSpec Start time
   * @param reason Reason
   * @param takeOnline Take online at end of maintenance
   * @param keepUpWhenActive Keep up while builds are running
   * @param maxWaitMinutes Max waiting time before canceling running builds.
   * @param duration Duration of the maintenance
   * @param userid Userid that created the maintenance window
   * @param id ID of the maintenance, use <code>null</code> to generate a new id
   * @param nextCheck timestamp when the next check should be performed
   * @throws ANTLRException When parsing the crontab list fails
   */
  @DataBoundConstructor
  public RecurringMaintenanceWindow(String startTimeSpec, String reason, boolean takeOnline, boolean keepUpWhenActive,
                                    String maxWaitMinutes, String duration, String userid, String id, long nextCheck)
      throws ANTLRException {
    this.startTimeSpec = startTimeSpec;
    this.tabs = CronTabList.create(startTimeSpec);
    this.reason = reason;
    this.takeOnline = takeOnline;
    this.maxWaitMinutes = maxWaitMinutes;
    this.keepUpWhenActive = keepUpWhenActive;
    this.duration = MaintenanceHelper.parseDurationString(duration);
    this.nextCheck = nextCheck;
    if (Util.fixEmptyAndTrim(userid) == null) {
      Authentication auth = Jenkins.getAuthentication2();
      userid = "System";
      if (auth != ACL.SYSTEM2) {
        userid = auth.getName();
      }
    }
    this.userid = userid;
    if (Util.fixEmptyAndTrim(id) == null) {
      id = UUID.randomUUID().toString();
    }
    this.id = id;
  }

  protected synchronized Object readResolve() throws ObjectStreamException {
    try {
      tabs = CronTabList.create(startTimeSpec);
    } catch (ANTLRException e) {
      InvalidObjectException x = new InvalidObjectException(e.getMessage());
      x.initCause(e);
      throw x;
    }
    return this;
  }

  public String getStartTimeSpec() {
    return startTimeSpec;
  }

  public int getDuration() {
    return duration;
  }

  public String getReason() {
    return reason;
  }

  public boolean isTakeOnline() {
    return takeOnline;
  }

  public boolean isKeepUpWhenActive() {
    return keepUpWhenActive;
  }

  public String getMaxWaitMinutes() {
    return maxWaitMinutes;
  }

  public String getUserid() {
    return userid;
  }

  @Restricted(NoExternalUse.class)
  public long getNextCheck() {
    return nextCheck;
  }

  @Restricted(NoExternalUse.class)
  public String getId() {
    return id;
  }

  /**
   * Returns a list of maintenance windows that should be put into the scheduled maintenance windows
   * of an agent.
   * Updates the nextCheck interval for the recurring window.
   *
   * @return The list of maintenance windows.
   */
  @NonNull
  @Restricted(NoExternalUse.class)
  public synchronized Set<MaintenanceWindow> getFutureMaintenanceWindows() {
    LOGGER.log(Level.FINER, "Checking for future maintenance Windows.");
    Calendar now = new GregorianCalendar();
    now.set(Calendar.SECOND, 0);

    Set<MaintenanceWindow> futureMaintenanceWindows = new TreeSet<>();
    if (now.getTimeInMillis() > nextCheck) {

      Calendar time = new GregorianCalendar();
      time.setTimeInMillis(nextCheck);
      time.set(Calendar.SECOND, 0);
      Calendar endCheckTime = (GregorianCalendar) time.clone();
      endCheckTime.add(Calendar.MINUTE, CHECK_INTERVAL_MINUTES);
      if (endCheckTime.before(now)) {
        endCheckTime = now;
        endCheckTime.add(Calendar.MINUTE, CHECK_INTERVAL_MINUTES);
      }
      Calendar nextCheckTime = (GregorianCalendar) endCheckTime.clone();

      time.add(Calendar.HOUR_OF_DAY, LEAD_TIME_DAYS * 24);
      if (time.before(now)) {
        time = (Calendar) now.clone();
      }
      endCheckTime.add(Calendar.HOUR_OF_DAY, LEAD_TIME_DAYS * 24);
      endCheckTime.add(Calendar.MINUTE, -1);

      LOGGER.log(Level.FINE, "Check for maintenance window starts between: {0} and {1}", new Object[] { time.getTime().toString(),
          endCheckTime.getTime().toString()});
      while (endCheckTime.after(time)) {
        if (tabs.check(time)) {
          LOGGER.log(Level.FINER, "Time matched: {0}", time.getTime().toString());
          futureMaintenanceWindows.add(getMaintenanceWindow(time));
        }
        time.add(Calendar.MINUTE, 1);
      }
      nextCheck = nextCheckTime.getTimeInMillis();
      LOGGER.log(Level.FINER, "Setting next Check time to: {0}", nextCheckTime.getTime().toString());
    }
    return futureMaintenanceWindows;
  }

  private MaintenanceWindow getMaintenanceWindow(Calendar time) {
    TimeZone tz = time.getTimeZone();
    ZoneId zoneId = tz.toZoneId();
    LocalDateTime startTime = LocalDateTime.ofInstant(time.toInstant(), zoneId);
    time.add(Calendar.MINUTE, duration);
    LocalDateTime endTime = LocalDateTime.ofInstant(time.toInstant(), zoneId);
    return new MaintenanceWindow(startTime, endTime, reason, takeOnline, keepUpWhenActive,
        maxWaitMinutes, userid, "");
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + duration;
    result = prime * result + (keepUpWhenActive ? 1231 : 1237);
    result = prime * result + ((maxWaitMinutes == null) ? 0 : maxWaitMinutes.hashCode());
    result = prime * result + ((reason == null) ? 0 : reason.hashCode());
    result = prime * result + ((startTimeSpec == null) ? 0 : startTimeSpec.hashCode());
    result = prime * result + (takeOnline ? 1231 : 1237);
    return result;
  }

  @Override
  @SuppressWarnings("checkstyle:NeedBraces")
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (getClass() != obj.getClass())
      return false;
    RecurringMaintenanceWindow other = (RecurringMaintenanceWindow) obj;
    if (startTimeSpec == null) {
      if (other.startTimeSpec != null)
        return false;
    } else if (!startTimeSpec.equals(other.startTimeSpec))
      return false;
    if (keepUpWhenActive != other.keepUpWhenActive)
      return false;
    if (!maxWaitMinutes.equals(other.maxWaitMinutes))
      return false;
    if (reason == null) {
      if (other.reason != null) {
        return false;
      }
    } else if (!reason.equals(other.reason))
      return false;
    if (takeOnline != other.takeOnline)
      return false;
    return true;
  }

  /** Descriptor for UI only. */
  @Extension
  public static class DescriptorImpl extends Descriptor<RecurringMaintenanceWindow> {

    @Override
    public String getDisplayName() {
      return "";
    }

    /**
     * Performs syntax check.
     */
    @POST
    public FormValidation doCheckStartTimeSpec(@QueryParameter String value) {
      try {
        String msg = CronTabList.create(fixNull(value)).checkSanity();
        if (msg != null) {
          return FormValidation.warning(msg);
        }
        return FormValidation.ok();
      } catch (ANTLRException e) {
        return FormValidation.error(e.getMessage());
      }
    }
  }
}