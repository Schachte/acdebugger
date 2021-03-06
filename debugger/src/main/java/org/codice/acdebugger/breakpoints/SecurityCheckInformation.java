/**
 * Copyright (c) Codice Foundation
 *
 * <p>This is free software: you can redistribute it and/or modify it under the terms of the GNU
 * Lesser General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or any later version.
 *
 * <p>This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details. A copy of the GNU Lesser General Public
 * License is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package org.codice.acdebugger.breakpoints;

// NOSONAR - squid:S1191 - Using the Java debugger API

import com.google.common.base.Charsets;
import com.google.common.collect.Ordering;
import com.google.common.io.LineProcessor;
import com.google.common.io.Resources;
import com.sun.jdi.ArrayReference; // NOSONAR
import com.sun.jdi.Location; // NOSONAR
import com.sun.jdi.ObjectReference; // NOSONAR
import com.sun.jdi.StackFrame; // NOSONAR
import com.sun.jdi.Value; // NOSONAR
import java.io.IOError;
import java.io.IOException;
import java.security.Permission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.codice.acdebugger.ACDebugger;
import org.codice.acdebugger.api.Debug;
import org.codice.acdebugger.api.SecurityFailure;
import org.codice.acdebugger.api.SecuritySolution;
import org.codice.acdebugger.api.StackFrameInformation;

/**
 * This class serves 2 purposes. It is first a representation of a detected security check failure.
 * During analysis, it is also used to compute and report possible security solutions to the
 * security failure.
 */
class SecurityCheckInformation extends SecuritySolution implements SecurityFailure {
  /**
   * List of patterns to match security check information that should be considered acceptable
   * failures and skipped.
   */
  private static final List<Pattern> ACCEPTABLE_PATTERNS;

  private static final String DOUBLE_LINES =
      "=======================================================================";

  static {
    try {
      ACCEPTABLE_PATTERNS =
          Resources.readLines(
              Resources.getResource("acceptable-security-check-failures.txt"),
              Charsets.UTF_8,
              new PatternProcessor());
    } catch (IOException e) {
      throw new IOError(e);
    }
  }

  /**
   * context array from AccessControlContext at line 472 converted to domain location/bundle names
   */
  private final List<String> context;

  /**
   * the current loop index through the domain context in the code where the breakpoint occurred or
   * <code>-1</code> if this is a solution in which case that index no longer matter since we are
   * faking what would happen if we granted permissions and/or extending privileges
   */
  @SuppressWarnings("squid:S00116" /* name is clearer that way */)
  private final int local_i;

  /** domain where the exception is about to be generated for */
  private final Value currentDomainValue;

  /** domain location/bundle name where the exception is about to be generated for */
  private final String currentDomain;

  /** list of protection domains (i.e. bundle name/domain location) in the security stack */
  private final List<String> domains;

  /* list of protection domains (i.e. bundle name/domain location) that are granted the failed permissions */
  private final Set<String> privilegedDomains;

  /**
   * index in the domains list where the security manager found the first domain that doesn't have
   * the failed permissions
   */
  private int failedDomainIndex = -1;

  /**
   * index in the domains list where the security manager started combining additional domains.
   * Below this mark are pure domains extracted from the current stack). At this mark and above are
   * domains that were combined. Will be <code>-1</code> if no combined domains exist.
   */
  private int combinedDomainsStartIndex = -1;

  /** the failed permission object */
  private final ObjectReference permission;

  /** the full stack trace at the point the security exception is about to be thrown */
  private final List<StackFrameInformation> stack;

  /**
   * the index in the stack for the code that doesn't have the failed permission, -1 if no failures
   */
  private int failedStackIndex = -1;

  /**
   * the stack index of that last place a domain extended its privileges or -1 if none everything
   * between 0 til this index is of interest for the security manager
   */
  private int privilegedStackIndex = -1;

  private final boolean invalid;

  /** First pattern that was matched indicating the failure was acceptable. */
  @Nullable private Pattern acceptablePattern = null;

  @Nullable private List<SecuritySolution> analysis = null;

  /**
   * Creates a security check failure.
   *
   * @param debug the current debug session
   * @param context the context of permission domain at the point of imminent failure
   * @param local_i the current loop index through the domain context in the code where the
   *     breakpoint occurred
   * @param permission the permission being checked
   * @throws Exception if unable to create a new security check failure
   */
  @SuppressWarnings("squid:S00117" /* name is clearer that way */)
  SecurityCheckInformation(
      Debug debug, ArrayReference context, int local_i, ObjectReference permission)
      throws Exception {
    this(debug, context.getValues(), local_i, permission);
  }

  /**
   * Creates a security check failure.
   *
   * @param debug the current debug session
   * @param context the context of permission domain at the point of imminent failure
   * @param local_i the current loop index through the domain context in the code where the
   *     breakpoint occurred
   * @param permission the permission being checked
   * @throws Exception if unable to create a new security check failure
   */
  @SuppressWarnings({
    "squid:S00117", /* name is clearer that way */
    "squid:S00112" /* Forced to by the Java debugger API */
  })
  private SecurityCheckInformation(
      Debug debug, List<Value> context, int local_i, ObjectReference permission) throws Exception {
    super(
        debug.permissions().getPermissionStrings(permission),
        Collections.emptySet(),
        Collections.emptyList());
    this.context = new ArrayList<>(context.size());
    this.local_i = local_i;
    this.currentDomainValue = context.get(local_i);
    this.currentDomain = debug.locations().get(currentDomainValue);
    this.domains = new ArrayList<>(context.size());
    this.permission = permission;
    this.privilegedDomains = new HashSet<>(context.size() * 3 / 2);
    for (int i = 0; i < context.size(); i++) {
      final Value domainValue = context.get(i);
      final String domain =
          (domainValue == currentDomainValue) ? currentDomain : debug.locations().get(domainValue);

      this.context.add(domain);
      if (i == local_i) {
        // we know we don't have privileges since we failed here so continue but only after
        // having added the current domain to the context list
        continue;
      } else if (i < local_i) { // we know we have privileges since we failed after `i`
        debug.permissions().grant(domain, permissionInfos);
        privilegedDomains.add(domain);
      } else if (domainValue instanceof ObjectReference) {
        if (debug.permissions().implies(domain, permissionInfos)) { // check cache
          privilegedDomains.add(domain);
        } else if (debug
            .permissions()
            .implies((ObjectReference) domainValue, permission)) { // check attached VM
          debug.permissions().grant(domain, permissionInfos);
          privilegedDomains.add(domain);
        }
      }
    }
    this.stack = new ArrayList<>(debug.thread().frameCount());
    // don't cache the set of stack as it will change every time we invoke()
    // something using the thread
    for (int i = 0; i < debug.thread().frameCount(); i++) {
      final StackFrame frame = debug.thread().frame(i);
      final Location location = frame.location(); // cache before we invoke anything on the thread
      final ObjectReference thisObject = frame.thisObject();
      final String domain = debug.locations().get(frame);
      final StackFrameInformation currentFrame =
          new StackFrameInformation(domain, location, thisObject);

      stack.add(currentFrame);
    }
    if (currentDomain == null) {
      // since bundle-0/boot domain always has all permissions, we cannot received null as the
      // current domain where the failure occurred
      dumpTroubleshootingInfo(
          debug, "AN ERROR OCCURRED WHILE ATTEMPTING TO FIND THE LOCATION FOR A DOMAIN");
      throw new Error("unable to find location for domain: " + currentDomainValue.type().name());
    }
    this.invalid = !recompute();
    if (!domains.contains(currentDomain)) {
      // it would seem we are unable to find the domain where we failed after recomputing
      dumpTroubleshootingInfo(
          debug,
          "AN ERROR OCCURRED WHILE ATTEMPTING ANALYZE THE SECURITY EXCEPTION, THE",
          "DOMAIN WHERE THE FAILURE WAS REPORTED CANNOT BE FOUND FROM THE CURRENT",
          "ACCESS CONTROL CONTEXT");
    }
    analyze0(debug);
  }

  /**
   * Creates a possible solution as if we granted the missing permission to the failed domain to be
   * analyzed for the given security check failure.
   *
   * @param failure the security check failure for which to create a possible solution
   */
  private SecurityCheckInformation(SecurityCheckInformation failure) {
    super(failure);
    this.context = failure.context;
    this.local_i = -1;
    // the combined domain start index is something fixed provided to us when the error is detected
    // this won't change because we are simply granting permissions to domains as this wouldn't
    // change the stack or the access control context as seen originally
    this.combinedDomainsStartIndex = failure.combinedDomainsStartIndex;
    this.currentDomainValue = failure.currentDomainValue;
    this.currentDomain = failure.currentDomain;
    this.domains = new ArrayList<>(failure.domains.size());
    this.privilegedDomains = new HashSet<>(failure.privilegedDomains);
    this.permission = failure.permission;
    this.stack = failure.stack;
    // add the failed domain from the specified failure as a privileged one
    privilegedDomains.add(failure.getFailedDomain());
    grantedDomains.add(failure.getFailedDomain());
    this.invalid = !recompute();
  }

  /**
   * Creates a possible solution as if we were extending privileges of the domain at the specified
   * stack index to be analyzed for the given security check failure.
   *
   * @param failure the security check failure for which to create a possible solution
   * @param index the index in the stack where to extend privileges
   */
  private SecurityCheckInformation(SecurityCheckInformation failure, int index) {
    super(failure);
    // because we are extending privileges which would be before any combined domains gets added to
    // the access control context, we can safely account for the fact that these combined domains
    // won't be present anymore in the new and revised access control context, as such we should
    // simply clear whatever start index would normally be inherited from the provided failure
    // we will also make sure that while recomputing the resulting failure, we ignore combined
    // domains provided by the original access control context
    this.context = failure.context;
    this.combinedDomainsStartIndex = -1;
    this.local_i = -1;
    this.currentDomainValue = failure.currentDomainValue;
    this.currentDomain = failure.currentDomain;
    this.domains = new ArrayList<>(failure.domains.size());
    this.privilegedDomains = failure.privilegedDomains;
    this.permission = failure.permission;
    this.stack = new ArrayList<>();
    final Set<String> newStackDomainsUpToDoPrivileged = new HashSet<>();

    // extend the stack by adding a fake doPrivileged() at the specified index and duplicating that
    // frame after it to show that it is still in the stack after the call to doPrivileged()
    for (int i = 0; i <= index; i++) {
      final StackFrameInformation frame = failure.stack.get(i);

      stack.add(frame);
      newStackDomainsUpToDoPrivileged.add(frame.getDomain());
    }
    stack.add(StackFrameInformation.DO_PRIVILEGED);
    doPrivileged.add(failure.stack.get(index));
    for (int i = index; i < failure.stack.size(); i++) {
      stack.add(failure.stack.get(i));
    }
    this.invalid = !recompute();
  }

  /**
   * Gets the domain where the failure was detected.
   *
   * @return the domain where the failure was detected or <code>null</code> if no failure is
   *     recomputed from the solution
   */
  @Nullable
  public String getFailedDomain() {
    return (failedDomainIndex != -1) ? domains.get(failedDomainIndex) : null;
  }

  @Override
  public Set<String> getPermissions() {
    return Collections.unmodifiableSet(permissionInfos);
  }

  @Override
  public List<StackFrameInformation> getStack() {
    return Collections.unmodifiableList(stack);
  }

  @Override
  public boolean isAcceptable() {
    return acceptablePattern != null;
  }

  @Override
  @Nullable
  public String getAcceptablePermissions() {
    return isAcceptable() ? "REGEX: " + acceptablePattern.getPermissionInfos() : null;
  }

  @Override
  public Set<String> getGrantedDomains() {
    return Collections.unmodifiableSet(grantedDomains);
  }

  @Override
  public List<StackFrameInformation> getDoPrivilegedLocations() {
    return Collections.unmodifiableList(doPrivileged);
  }

  @Override
  public List<SecuritySolution> analyze() {
    return analysis;
  }

  @SuppressWarnings("squid:S106" /* this is a console application */)
  @Override
  public void dump(boolean osgi, String prefix) {
    final String first =
        prefix + (isAcceptable() ? "ACCEPTABLE " : "") + "ACCESS CONTROL PERMISSION FAILURE";

    System.out.println(ACDebugger.PREFIX);
    System.out.println(ACDebugger.PREFIX + first);
    System.out.println(
        ACDebugger.PREFIX
            + IntStream.range(0, first.length())
                .mapToObj(i -> "=")
                .collect(Collectors.joining("")));
    dump0(osgi);
    if (!isAcceptable()) {
      for (int i = 0; i < analysis.size(); i++) {
        final SecuritySolution info = analysis.get(i);

        System.out.println(ACDebugger.PREFIX);
        System.out.println(ACDebugger.PREFIX + "OPTION " + (i + 1));
        System.out.println(ACDebugger.PREFIX + "--------");
        ((SecurityCheckInformation) info).dump0(osgi);
      }
      if (!analysis.isEmpty()) {
        System.out.println(ACDebugger.PREFIX);
        System.out.println(ACDebugger.PREFIX + "SOLUTIONS");
        System.out.println(ACDebugger.PREFIX + "---------");
        analysis.forEach(s -> s.print(osgi));
      }
    }
  }

  @Override
  public String toString() {
    if (currentDomain == null) {
      return "";
    }
    if (isAcceptable()) {
      return "Acceptable check permissions failure for "
          + currentDomain
          + ": "
          + getAcceptablePermissions();
    } else {
      if (permissionInfos.size() == 1) {
        return "Check permission failure for "
            + currentDomain
            + ": "
            + permissionInfos.iterator().next();
      }
      return "Check permissions failure for " + currentDomain + ": " + permissionInfos;
    }
  }

  /**
   * This method reproduces what the {@link
   * java.security.AccessControlContext#checkPermission(Permission)} does whenever it checks for
   * permissions. It goes through the stack and builds a list of domains based on each stack frame
   * encountered. If the domain is already known it moves on. if it encounters the doPrivileged()
   * block, it stops processing the stack. As it goes through it, it checks if the corresponding
   * domain implies() the permission it currently checks and if not, it would normally generate the
   * exception.
   *
   * <p>By re-implementing this logic, we can now see what would happen if we change permissions or
   * if we extend privileges at a specific location in our code. It actually allows us to verify if
   * there would be a line later that would create another problem.
   *
   * <p>When the breakpoint is invoked, we could extract that information from the loop in the
   * <code>AccessControlContext.checkPermission()</code> method. But instead of doing that, it is
   * simpler to keep the same logic to recompute. I kept the code around and left it in the private
   * constructor with the dummy parameter.
   *
   * <p>We shall also check the stack and the failed permission against all acceptable patterns and
   * if one matches, we will skip mark it as acceptable.
   *
   * @return <code>true</code> if all granted domains were required; <code>false</code> if we didn't
   *     need all of them which would mean this is an invalid option as we are granting more than we
   *     need
   */
  @SuppressWarnings("squid:CommentedOutCodeLine" /* no commented out code here */)
  private boolean recompute() {
    domains.clear();
    this.privilegedStackIndex = -1;
    this.failedStackIndex = -1;
    final Set<String> grantedDomains = new HashSet<>(this.grantedDomains);
    final boolean foundFailedDomain = recomputeFromStack(grantedDomains);

    if (doPrivileged.isEmpty()) {
      // make sure we account for all inherited/combined domains in the access control context. In
      // case where a combiner was used, it is possible that additional domains from an inherited
      // access control context be added to the list that the access controller is checking against.
      // If we have more than we need to remember as there will be no stack lines that will
      // correspond to these domains
      recomputeFromContext(grantedDomains, foundFailedDomain);
    } else {
      // if we are extending privileges then we are modifying the stack and the access control
      // context that would now result from the same exception we are trying to recompute.
      // because we are extending the stack before any places where we were combining domains there
      // is no way combined domains would leak into this solution
      // we are guaranteed that we are doing this before because:
      // 1- we cannot analyze stack from combined domains as those are not from a stack execution;
      //    hence we cannot propose to extend privileges there
      // 2- to combined domains, one must extend privileges via the AccessController which means
      //    this would already be the last line in the stack we computed before and as such, the
      //    privileged block we are proposing would be before that particular line any way
      this.combinedDomainsStartIndex = -1; // just make sure even though the ctor would have done it
    }
    return grantedDomains.isEmpty();
  }

  private void recomputeFromContext(Set<String> grantedDomains, boolean foundFailedDomain) {
    for (int i = 0; i < context.size(); i++) {
      final String domain = context.get(i);

      if ((domain != null) && !domains.contains(domain)) {
        domains.add(domain);
        if (combinedDomainsStartIndex == -1) {
          this.combinedDomainsStartIndex = domains.size() - 1;
        }
        if (!foundFailedDomain) {
          if (!privilegedDomains.contains(domain)) { // found the place it will fail!!!!
            foundFailedDomain = true;
            this.failedDomainIndex = domains.size() - 1;
          } else {
            // keep track of the fact that this granted domain helped if it was one
            // that we artificially granted the permission to
            grantedDomains.remove(domain);
          }
        }
      }
    }
  }

  private void updatePrivilegedStackIndex(int i) {
    this.privilegedStackIndex = i + 1;
    // special case to handle situations like javax.security.auth.Subject:422
    // which ends up doing a doPrivileged() on behalf of the caller
    while (true) {
      final StackFrameInformation nextFrame = stack.get(privilegedStackIndex);

      if (!nextFrame.isCallingDoPrivilegedBlockOnBehalfOfCaller()) {
        break;
      }
      this.privilegedStackIndex++;
    }
  }

  private boolean recomputeFromStack(Set<String> grantedDomains) {
    final List<Pattern> stackPatterns =
        SecurityCheckInformation.ACCEPTABLE_PATTERNS
            .stream()
            .filter(p -> p.matchAllPermissions(permissionInfos))
            .map(Pattern::new)
            .collect(Collectors.toList());
    boolean foundFailedDomain = false;

    for (int i = 0; i < stack.size(); i++) {
      final StackFrameInformation frame = stack.get(i);

      if (frame.isDoPrivilegedBlock()) { // stop here
        updatePrivilegedStackIndex(i);
        break;
      }
      final String location = frame.getLocation();

      if (!isAcceptable()) {
        final int index = i;

        this.acceptablePattern =
            stackPatterns
                .stream()
                .filter(p -> p.matchLocations(index, location))
                .filter(Pattern::wasAllMatched)
                .findFirst()
                .orElse(null);
      }
      final String domain = frame.getDomain();

      if ((domain != null) && !domains.contains(domain)) {
        domains.add(domain);
      }
      if (!foundFailedDomain) {
        if (!frame.isPrivileged(privilegedDomains)) { // found the place where it will fail!!!!
          foundFailedDomain = true;
          this.failedStackIndex = i;
          this.failedDomainIndex = domains.indexOf(domain);
        } else {
          // keep track of the fact that this granted domain helped if it was one
          // that we artificially granted the permission to
          grantedDomains.remove(frame.getDomain());
        }
      }
    }
    return foundFailedDomain;
  }

  private List<SecuritySolution> analyze0(Debug debug) {
    if (analysis == null) {
      if (invalid) { // if this is not a valid solution then the analysis should be empty
        this.analysis = Collections.emptyList();
      } else if (((failedStackIndex == -1) && (failedDomainIndex == -1)) || isAcceptable()) {
        // no issues here (i.e. good solution) or acceptable security exception so return self
        this.analysis = Collections.singletonList(this);
      } else {
        this.analysis = new ArrayList<>();
        // first see what happens if we grant the missing permission to the failed domain
        analysis.addAll(new SecurityCheckInformation(this).analyze0(debug));
        if (debug.canDoPrivilegedBlocks()) {
          analyzeDoPrivilegedBlocks(debug);
        }
        this.analysis = Ordering.natural().sortedCopy(analysis); // sort the result
      }
    }
    return analysis;
  }

  private void analyzeDoPrivilegedBlocks(Debug debug) {
    // now check if we could extend the privileges of a domain that comes up
    // before which already has the permission
    for (int i = failedStackIndex - 1; i >= 0; i--) {
      final StackFrameInformation frame = stack.get(i);

      if (frame.isPrivileged(privilegedDomains) && frame.canDoPrivilegedBlocks(debug)) {
        analysis.addAll(new SecurityCheckInformation(this, i).analyze0(debug));
      }
    }
  }

  @SuppressWarnings("squid:S106" /* this is a console application */)
  private void dumpPermission() {
    if (isAcceptable()) {
      System.out.println(ACDebugger.PREFIX + "Acceptable permissions:");
      System.out.println(ACDebugger.PREFIX + "    " + getAcceptablePermissions());
    } else {
      final String s = (permissionInfos.size() == 1) ? "" : "s";

      System.out.println(ACDebugger.PREFIX + "Permission" + s + ":");
      permissionInfos.forEach(p -> System.out.println(ACDebugger.PREFIX + "    " + p));
    }
  }

  @SuppressWarnings("squid:S106" /* this is a console application */)
  private void dumpHowToFix(boolean osgi) {
    if (!grantedDomains.isEmpty()) {
      final String ds = (grantedDomains.size() == 1) ? "" : "s";
      final String ps = (permissionInfos.size() == 1) ? "" : "s";

      System.out.println(
          ACDebugger.PREFIX
              + "Granting permission"
              + ps
              + " to "
              + (osgi ? "bundle" : "domain")
              + ds
              + ":");
      grantedDomains.forEach(d -> System.out.println(ACDebugger.PREFIX + "    " + d));
    }
    if (!doPrivileged.isEmpty()) {
      System.out.println(ACDebugger.PREFIX + "Extending privileges at:");
      doPrivileged.forEach(f -> System.out.println(ACDebugger.PREFIX + "    " + f));
    }
  }

  @SuppressWarnings("squid:S106" /* this is a console application */)
  private void dumpContext(boolean osgi) {
    System.out.println(ACDebugger.PREFIX + "Context:");
    System.out.println(
        ACDebugger.PREFIX
            + "     "
            + (osgi ? StackFrameInformation.BUNDLE0 : StackFrameInformation.BOOT_DOMAIN));
    for (int i = 0; i < domains.size(); i++) {
      final String domain = domains.get(i);

      System.out.println(
          ACDebugger.PREFIX
              + " "
              + ((i == failedDomainIndex) ? "--> " : "    ")
              + (privilegedDomains.contains(domain) ? "" : "*")
              + domain
              + (((i >= combinedDomainsStartIndex) && (combinedDomainsStartIndex != -1))
                  ? " (combined)"
                  : ""));
    }
  }

  @SuppressWarnings("squid:S106" /* this is a console application */)
  private void dumpStack(boolean osgi) {
    System.out.println(ACDebugger.PREFIX + "Stack:");
    for (int i = 0; i < stack.size(); i++) {
      System.out.println(
          ACDebugger.PREFIX
              + " "
              + ((i == failedStackIndex) ? "-->" : "   ")
              + " at "
              + (isAcceptable() && acceptablePattern.wasMatched(i) ? "#" : "")
              + stack.get(i).toString(osgi, privilegedDomains));
      if ((privilegedStackIndex != -1) && (i == privilegedStackIndex)) {
        System.out.println(
            ACDebugger.PREFIX + "    ----------------------------------------------------------");
      }
    }
  }

  private void dump0(boolean osgi) {
    dumpPermission();
    dumpHowToFix(osgi);
    dumpContext(osgi);
    dumpStack(osgi);
  }

  @SuppressWarnings("squid:S106" /* this is a console application */)
  private void dumpTroubleshootingInfo(Debug debug, String... msg) {
    System.err.println(ACDebugger.PREFIX);
    System.err.println(ACDebugger.PREFIX + SecurityCheckInformation.DOUBLE_LINES);
    Stream.of(msg).map(ACDebugger.PREFIX::concat).forEach(System.err::println);
    System.err.println(
        ACDebugger.PREFIX
            + "PLEASE REPORT AN ISSUE WITH THE FOLLOWING INFORMATION AND INSTRUCTIONS");
    System.err.println(ACDebugger.PREFIX + "ON HOW TO REPRODUCE IT");
    System.err.println(ACDebugger.PREFIX + SecurityCheckInformation.DOUBLE_LINES);
    System.err.println(
        ACDebugger.PREFIX + "CURRENT DOMAIN CLASS: " + currentDomainValue.type().name());
    System.err.println(ACDebugger.PREFIX + "CURRENT DOMAIN: " + currentDomainValue);
    System.err.println(ACDebugger.PREFIX + "LOCAL 'i' VARIABLE: " + local_i);
    System.err.println(ACDebugger.PREFIX + "ACCESS CONTROL CONTEXT:");
    context.forEach(b -> System.err.println(ACDebugger.PREFIX + "  " + b));
    System.err.println(ACDebugger.PREFIX + "CONTEXT:");
    System.err.println(
        ACDebugger.PREFIX
            + "  "
            + (debug.isOSGi() ? StackFrameInformation.BUNDLE0 : StackFrameInformation.BOOT_DOMAIN));
    for (int i = 0; i < domains.size(); i++) {
      System.err.println(
          ACDebugger.PREFIX
              + "  "
              + domains.get(i)
              + (((i >= combinedDomainsStartIndex) && (combinedDomainsStartIndex != -1))
                  ? " (combined)"
                  : ""));
    }
    System.err.println(ACDebugger.PREFIX + "STACK:");
    for (int i = 0; i < stack.size(); i++) {
      System.err.println(ACDebugger.PREFIX + "  at " + stack.get(i));
      if ((privilegedStackIndex != -1) && (i == privilegedStackIndex)) {
        System.err.println(
            ACDebugger.PREFIX + "    ----------------------------------------------------------");
      }
    }
    System.err.println(ACDebugger.PREFIX + SecurityCheckInformation.DOUBLE_LINES);
  }

  /** Pattern class for matching specific permission and stack information. */
  private static class Pattern {
    private final java.util.regex.Pattern permissionPattern;
    private final List<java.util.regex.Pattern> stackPatterns;
    private final List<Integer> stackIndexes;

    private Pattern(String permissionPattern) {
      this.permissionPattern = java.util.regex.Pattern.compile(permissionPattern);
      this.stackPatterns = new ArrayList<>(8);
      this.stackIndexes = null;
    }

    public Pattern(Pattern pattern) {
      this.permissionPattern = pattern.permissionPattern;
      this.stackPatterns = new ArrayList<>(pattern.stackPatterns);
      this.stackIndexes = new ArrayList<>(stackPatterns.size());
    }

    private void addStack(String stackPattern) {
      stackPatterns.add(java.util.regex.Pattern.compile(stackPattern));
    }

    @SuppressWarnings("squid:S00112" /* Forced to by the Java debugger API */)
    private void validate() {
      if (stackPatterns.isEmpty()) {
        throw new Error(
            "missing stack frame information for [" + permissionPattern.pattern() + "]");
      }
    }

    public String getPermissionInfos() {
      return permissionPattern.pattern();
    }

    public boolean matchAllPermissions(Set<String> permissionInfos) {
      return permissionInfos.stream().map(permissionPattern::matcher).allMatch(Matcher::matches);
    }

    public boolean matchLocations(int index, String location) {
      if (!stackPatterns.isEmpty() && stackPatterns.get(0).matcher(location).matches()) {
        stackPatterns.remove(0);
        stackIndexes.add(index);
        return true;
      }
      return false;
    }

    public boolean wasAllMatched() {
      return stackPatterns.isEmpty();
    }

    public boolean wasMatched(int index) {
      return stackIndexes.contains(index);
    }
  }

  /** Line processors for returning a list of patterns while trimming and ignoring comment lines. */
  private static class PatternProcessor implements LineProcessor<List<Pattern>> {
    private final List<Pattern> result = new ArrayList<>();
    private Pattern current = null;

    @Override
    public boolean processLine(String line) throws IOException {
      final String trimmed = line.trim();

      if (trimmed.startsWith("#")) { // nothing to do, just skip that line and continues
      } else if (trimmed.isEmpty()) {
        if (current != null) {
          current.validate();
          this.current = null;
        }
      } else if (current == null) {
        this.current = new Pattern(trimmed);
        result.add(current);
      } else {
        current.addStack(trimmed);
      }
      return true;
    }

    @Override
    public List<Pattern> getResult() {
      if (current != null) {
        current.validate();
        this.current = null;
      }
      return Collections.unmodifiableList(result);
    }
  }
}
