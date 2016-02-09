package com.jetbrains.lang.dart.ide.runner.test;

import com.google.gson.*;
import com.intellij.execution.testframework.TestConsoleProperties;
import com.intellij.execution.testframework.sm.ServiceMessageBuilder;
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.jetbrains.lang.dart.ide.runner.util.DartTestLocationProvider;
import jetbrains.buildServer.messages.serviceMessages.ServiceMessageVisitor;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Type;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Convert events from JSON format generated by package:test to the string format
 * expected by the event processor.
 * NOTE: The test runner runs tests asynchronously. It is possible to get a 'testDone'
 * event followed some time later by an 'error' event for that same test. That should
 * convert a successful test into a failure. That case is not being handled.
 */
public class DartTestEventsConverter extends OutputToGeneralTestEventsConverter {
  private static final Logger LOG = Logger.getInstance(DartTestEventsConverter.class.getName());

  private static final String TYPE_START = "start";
  private static final String TYPE_SUITE = "suite";
  private static final String TYPE_ERROR = "error";
  private static final String TYPE_GROUP = "group";
  private static final String TYPE_PRINT = "print";
  private static final String TYPE_DONE = "done";
  private static final String TYPE_ALL_SUITES = "allSuites";
  private static final String TYPE_TEST_START = "testStart";
  private static final String TYPE_TEST_DONE = "testDone";

  private static final String DEF_GROUP = "group";
  private static final String DEF_SUITE = "suite";
  private static final String DEF_TEST = "test";
  private static final String DEF_METADATA = "metadata";

  private static final String JSON_TYPE = "type";
  private static final String JSON_NAME = "name";
  private static final String JSON_ID = "id";
  private static final String JSON_TEST_ID = "testID";
  private static final String JSON_SUITE_ID = "suiteID";
  private static final String JSON_PARENT_ID = "parentID";
  private static final String JSON_GROUP_IDS = "groupIDs";
  private static final String JSON_RESULT = "result";
  private static final String JSON_HIDDEN = "hidden";
  private static final String JSON_MILLIS = "time";
  private static final String JSON_COUNT = "count";
  private static final String JSON_TEST_COUNT = "testCount";
  private static final String JSON_MESSAGE = "message";
  private static final String JSON_ERROR_MESSAGE = "error";
  private static final String JSON_STACK_TRACE = "stackTrace";
  private static final String JSON_IS_FAILURE = "isFailure";
  private static final String JSON_PATH = "path";
  private static final String JSON_PLATFORM = "platform";

  private static final String RESULT_SUCCESS = "success";
  private static final String RESULT_FAILURE = "failure";
  private static final String RESULT_ERROR = "error";

  private static final String NEWLINE = "\n";
  private static final String EXPECTED = "Expected: ";
  private static final Pattern EXPECTED_ACTUAL_RESULT = Pattern.compile("\\nExpected: (.*)\\n  Actual: (.*)\\n *\\^\\n Differ.*\\n");
  private static final String FAILED_TO_LOAD = "Failed to load ";
  private static final String FILE_URL_PREFIX = "dart_location://";
  private static final String LOADING_PREFIX = "loading ";

  private static final Gson GSON = new Gson();

  // In theory, test events could be generated asynchronously and out of order. We might want to keep a map of tests to start times
  // so we get accurate durations when tests end. See myTestData.
  private long myStartMillis;
  private String myLocation;
  private boolean myOutputAppeared = false;
  private Key myCurrentOutputType;
  private ServiceMessageVisitor myCurrentVisitor;
  private Map<Integer, Test> myTestData;
  private Map<Integer, Group> myGroupData;
  private Map<Integer, Suite> mySuiteData;
  private int mySuitCount;

  public DartTestEventsConverter(@NotNull final String testFrameworkName, @NotNull final TestConsoleProperties consoleProperties) {
    super(testFrameworkName, consoleProperties);
    myTestData = new HashMap<Integer, Test>();
    myGroupData = new HashMap<Integer, Group>();
    mySuiteData = new HashMap<Integer, Suite>();
  }

  protected boolean processServiceMessages(final String text, final Key outputType, final ServiceMessageVisitor visitor)
    throws ParseException {
    LOG.debug("<<< " + text.trim());
    myCurrentOutputType = outputType;
    myCurrentVisitor = visitor;
    // service message parser expects line like "##teamcity[ .... ]" without whitespaces in the end.
    return processEventText(text);
  }

  private boolean processEventText(final String text) throws JsonSyntaxException, ParseException {
    JsonParser jp = new JsonParser();
    JsonElement elem;
    try {
      elem = jp.parse(text);
    }
    catch (JsonSyntaxException ex) {
      if (text.contains("\"json\" is not an allowed value for option \"reporter\"")) {
        failedToLoad("Please update your pubspec.yaml dependency on package:test to version 0.12.7 or later.", 0);
      }
      return doProcessServiceMessages(text);
    }
    if (elem == null || !elem.isJsonObject()) return false;
    return process(elem.getAsJsonObject());
  }

  private boolean doProcessServiceMessages(@NotNull final String text) throws ParseException {
    LOG.debug(">>> " + text);
    return super.processServiceMessages(text, myCurrentOutputType, myCurrentVisitor);
  }

  private boolean process(JsonObject obj) throws JsonSyntaxException, ParseException {
    String type = obj.get(JSON_TYPE).getAsString();
    if (TYPE_TEST_START.equals(type)) {
      return handleTestStart(obj);
    }
    else if (TYPE_TEST_DONE.equals(type)) {
      return handleTestDone(obj);
    }
    else if (TYPE_ERROR.equals(type)) {
      return handleError(obj);
    }
    else if (TYPE_PRINT.equals(type)) {
      return handlePrint(obj);
    }
    else if (TYPE_GROUP.equals(type)) {
      return handleGroup(obj);
    }
    else if (TYPE_SUITE.equals(type)) {
      return handleSuite(obj);
    }
    else if (TYPE_ALL_SUITES.equals(type)) {
      return handleAllSuites(obj);
    }
    else if (TYPE_START.equals(type)) {
      return handleStart(obj);
    }
    else if (TYPE_DONE.equals(type)) {
      return handleDone(obj);
    }
    else {
      return true;
    }
  }

  private boolean handleTestStart(JsonObject obj) throws ParseException {
    JsonObject testObj = obj.getAsJsonObject(DEF_TEST);
    // Not reached if testObj == null.
    Test test = getTest(obj);
    if (!test.hasValidParent() && test.getName().startsWith(LOADING_PREFIX)) {
      String path = test.getName().substring(LOADING_PREFIX.length());
      if (path.length() > 0) myLocation = FILE_URL_PREFIX + path;
      setNotRunning(test);
      return true;
    }
    String testName = test.getBaseName();
    ServiceMessageBuilder testStarted = ServiceMessageBuilder.testStarted(testName);
    addLocationHint(testStarted, test);
    myStartMillis = getTestMillis(obj);
    myOutputAppeared = false;
    boolean result = finishMessage(testStarted, test.getId(), test.getValidParentId());
    if (result) {
      Metadata metadata = Metadata.from(testObj.getAsJsonObject(DEF_METADATA));
      if (metadata.skip) {
        ServiceMessageBuilder message = ServiceMessageBuilder.testIgnored(testName);
        if (metadata.skipReason != null) message.addAttribute("message", metadata.skipReason);
        return finishMessage(message, test.getId(), test.getValidParentId());
      }
    }
    return result;
  }

  private boolean handleTestDone(JsonObject obj) throws ParseException {
    if (getBoolean(obj, JSON_HIDDEN)) return true;
    String result = getResult(obj);
    if (result.equals(RESULT_SUCCESS)) {
      return eventFinished(obj);
    }
    else if (result.equals(RESULT_FAILURE)) {
      return true;
    }
    else if (result.equals(RESULT_ERROR)) {
      return true;
    }
    else {
      throw new ParseException("Unknown result: " + obj, 0);
    }
  }

  private boolean handleGroup(JsonObject obj) throws ParseException {
    Group group = getGroup(obj.getAsJsonObject(DEF_GROUP));

    // From spec: The implicit group at the root of each test suite has null name and parentID attributes.
    if (group.getParent() == null && group.getTestCount() > 0) {
      // com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter.MyServiceMessageVisitor.KEY_TESTS_COUNT
      // and  com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter.MyServiceMessageVisitor.ATTR_KEY_TEST_COUNT
      final ServiceMessageBuilder testCount =
        new ServiceMessageBuilder("testCount").addAttribute("count", String.valueOf(group.getTestCount()));
      doProcessServiceMessages(testCount.toString());
    }

    if (group.isArtificial()) return true; // Ignore artificial groups.
    ServiceMessageBuilder groupMsg = ServiceMessageBuilder.testSuiteStarted(group.getBaseName());
    // Possible attributes: "nodeType" "nodeArgs" "running"
    addLocationHint(groupMsg, group);
    return finishMessage(groupMsg, group.getId(), group.getValidParentId());
  }

  private boolean handleSuite(JsonObject obj) throws ParseException {
    Suite suite = getSuite(obj.getAsJsonObject(DEF_SUITE));
    if (!suite.hasPath()) {
      mySuiteData.remove(suite.getId());
    }
    return true;
  }

  private boolean handleError(JsonObject obj) throws ParseException {
    String message = getErrorMessage(obj);
    if (message.startsWith(FAILED_TO_LOAD)) {
      // An error due to loading failure probably was preceded by a start event that was not recorded since it is not a test.
      JsonElement elem = obj.get(JSON_TEST_ID);
      if (elem != null &&
          elem.isJsonPrimitive() &&
          (myTestData.get(elem.getAsInt()) == null || !myTestData.get(elem.getAsInt()).hasValidParent())) {
        return failedToLoad(message, elem.getAsInt());
      }
    }
    Test test = getTest(obj);
    setNotRunning(test);

    final ServiceMessageBuilder testError = ServiceMessageBuilder.testFailed(test.getBaseName());

    String failureMessage = message;
    int firstExpectedIndex = message.indexOf(EXPECTED);
    if (firstExpectedIndex >= 0) {
      Matcher matcher = EXPECTED_ACTUAL_RESULT.matcher(message);
      if (matcher.find(firstExpectedIndex + EXPECTED.length())) {
        String expectedText = matcher.group(1);
        String actualText = matcher.group(2);
        testError.addAttribute("expected", expectedText);
        testError.addAttribute("actual", actualText);
        if (firstExpectedIndex == 0) {
          failureMessage = "Comparison failed";
        }
        else {
          failureMessage = message.substring(0, firstExpectedIndex);
        }
      }
    }

    // The stack trace could be null, but we disallow that for consistency with all the transmitted values.
    if (!getBoolean(obj, JSON_IS_FAILURE)) testError.addAttribute("error", "true");
    testError.addAttribute("message", failureMessage + NEWLINE);

    long duration = getTestMillis(obj) - myStartMillis;
    testError.addAttribute("duration", Long.toString(duration));

    final ServiceMessageBuilder testStdErr = ServiceMessageBuilder.testStdErr(test.getBaseName());
    testStdErr.addAttribute("out", getStackTrace(obj));

    final ServiceMessageBuilder testFinished = ServiceMessageBuilder.testFinished(test.getBaseName());
    testFinished.addAttribute("duration", Long.toString(duration));

    return finishMessage(testError, test.getId(), test.getValidParentId()) &&
           finishMessage(testStdErr, test.getId(), test.getValidParentId()) &&
           finishMessage(testFinished, test.getId(), test.getValidParentId());
  }

  private boolean handleAllSuites(JsonObject obj) {
    JsonElement elem = obj.get(JSON_TEST_COUNT);
    if (elem == null || !elem.isJsonPrimitive()) return true;
    mySuitCount = elem.getAsInt();
    return true;
  }

  private boolean handlePrint(JsonObject obj) throws ParseException {
    Test test = getTest(obj);
    ServiceMessageBuilder message = ServiceMessageBuilder.testStdOut(test.getBaseName());
    String out;
    if (myOutputAppeared) {
      out = getMessage(obj) + NEWLINE;
    }
    else {
      out = NEWLINE + getMessage(obj) + NEWLINE;
    }
    message.addAttribute("out", out);
    myOutputAppeared = true;
    return finishMessage(message, test.getId(), test.getValidParentId());
  }

  private boolean handleStart(JsonObject obj) {
    myTestData.clear();
    myGroupData.clear();
    mySuiteData.clear();
    mySuitCount = 0;
    // This apparently is a no-op: myProcessor.signalTestFrameworkAttached();
    return true;
  }

  private boolean handleDone(JsonObject obj) throws ParseException {
    // The test runner has reached the end of the tests.
    processAllTestsDone();
    return true;
  }

  private void processAllTestsDone() {
    // All tests are done.
    for (Group group : myGroupData.values()) {
      // There is no 'groupDone' event, due to asynchrony, so finish them all at the end.
      // AFAIK the order does not matter. A depth-first post-order traversal of the tree would work
      // if order does matter. Note: Currently, there is no tree representation, just parent links.
      try {
        processGroupDone(group);
      }
      catch (ParseException ex) {
        // ignore it
      }
    }
    myTestData.clear();
    myGroupData.clear();
    mySuiteData.clear();
    mySuitCount = 0;
  }

  private boolean processGroupDone(Group group) throws ParseException {
    if (group.isArtificial()) return true;
    ServiceMessageBuilder groupMsg = ServiceMessageBuilder.testSuiteFinished(group.getBaseName());
    finishMessage(groupMsg, group.getId(), group.getValidParentId());
    return true;
  }

  private boolean eventFinished(JsonObject obj) throws ParseException {
    Test test = getTest(obj);
    setNotRunning(test);
    if (test.getMetadata().skip) return true;
    // Since we cannot tell when a group is finished always reset the parent ID.
    long duration = getTestMillis(obj) - myStartMillis;
    ServiceMessageBuilder testFinished = ServiceMessageBuilder.testFinished(test.getBaseName());
    testFinished.addAttribute("duration", Long.toString(duration));
    return finishMessage(testFinished, test.getId(), test.getValidParentId());
  }

  private boolean finishMessage(@NotNull ServiceMessageBuilder msg, int testId, int parentId) throws ParseException {
    msg.addAttribute("nodeId", String.valueOf(testId));
    msg.addAttribute("parentNodeId", String.valueOf(parentId));
    return doProcessServiceMessages(msg.toString());
  }

  private boolean failedToLoad(String message, int testId) throws ParseException {
    int syntheticTestId = testId + 1;
    ServiceMessageBuilder testStarted = ServiceMessageBuilder.testStarted("Failed to load");
    finishMessage(testStarted, syntheticTestId, 0);
    ServiceMessageBuilder testFailed = ServiceMessageBuilder.testFailed("Failed to load");
    testFailed.addAttribute("message", message);
    finishMessage(testFailed, syntheticTestId, 0);
    return true;
  }

  private void addLocationHint(ServiceMessageBuilder messageBuilder, Item item) {
    String location = "unknown";
    String loc;
    if (item.hasSuite()) {
      loc = FILE_URL_PREFIX + item.getSuite().getPath();
    }
    else {
      loc = myLocation;
    }
    if (loc != null) {
      String nameList = GSON.toJson(item.nameList(), DartTestLocationProvider.STRING_LIST_TYPE);
      location = loc + "," + nameList;
    }
    messageBuilder.addAttribute("locationHint", location);
  }

  private static void setNotRunning(Test test) {
    test.isRunning = false;
  }

  private static long getTestMillis(JsonObject obj) throws ParseException {
    return getLong(obj, JSON_MILLIS);
  }

  private static long getLong(JsonObject obj, String name) throws ParseException {
    JsonElement val = obj == null ? null : obj.get(name);
    if (val == null || !val.isJsonPrimitive()) throw new ParseException("Value is not type long: " + val, 0);
    return val.getAsLong();
  }

  private static boolean getBoolean(JsonObject obj, String name) throws ParseException {
    JsonElement val = obj == null ? null : obj.get(name);
    if (val == null || !val.isJsonPrimitive()) throw new ParseException("Value is not type boolean: " + val, 0);
    return val.getAsBoolean();
  }

  @NotNull
  private Test getTest(JsonObject obj) throws ParseException {
    return getItem(obj, myTestData);
  }

  @NotNull
  private Group getGroup(JsonObject obj) throws ParseException {
    return getItem(obj, myGroupData);
  }

  @NotNull
  private Suite getSuite(JsonObject obj) throws ParseException {
    return getItem(obj, mySuiteData);
  }

  @NotNull
  private <T extends Item> T getItem(JsonObject obj, Map<Integer, T> items) throws ParseException {
    if (obj == null) throw new ParseException("Unexpected null json object", 0);
    T item;
    JsonElement id = obj.get(JSON_ID);
    if (id != null) {
      if (items == myTestData) {
        @SuppressWarnings("unchecked") T type = (T)Test.from(obj, myGroupData, mySuiteData);
        item = type;
      }
      else if (items == myGroupData) {
        @SuppressWarnings("unchecked") T group = (T)Group.from(obj, myGroupData, mySuiteData);
        item = group;
      }
      else {
        @SuppressWarnings("unchecked") T suite = (T)Suite.from(obj);
        item = suite;
      }
      items.put(id.getAsInt(), item);
    }
    else {
      JsonElement testId = obj.get(JSON_TEST_ID);
      if (testId != null) {
        int baseId = testId.getAsInt();
        item = items.get(baseId);
      }
      else {
        JsonElement testObj = obj.get(DEF_TEST);
        if (testObj != null) {
          return getItem(testObj.getAsJsonObject(), items);
        }
        else {
          throw new ParseException("No testId in json object", 0);
        }
      }
    }
    return item;
  }

  @NotNull
  private static String getErrorMessage(JsonObject obj) {
    return nonNullJsonValue(obj, JSON_ERROR_MESSAGE, "<no error message>");
  }

  @NotNull
  private static String getMessage(JsonObject obj) {
    return nonNullJsonValue(obj, JSON_MESSAGE, "<no message>");
  }

  @NotNull
  private static String getStackTrace(JsonObject obj) {
    return nonNullJsonValue(obj, JSON_STACK_TRACE, "<no stack trace>");
  }

  @NotNull
  private static String getResult(JsonObject obj) {
    return nonNullJsonValue(obj, JSON_RESULT, "<no result>");
  }

  @NotNull
  private static String nonNullJsonValue(JsonObject obj, @NotNull String id, @NotNull String def) {
    JsonElement val = obj == null ? null : obj.get(id);
    if (val == null || !val.isJsonPrimitive()) return def;
    return val.getAsString();
  }

  private static class Item {
    private static final String NO_NAME = "<no name>";
    private final int myId;
    private final String myName;
    private final Group myParent;
    private final Suite mySuite;
    private final Metadata myMetadata;

    static int extractId(JsonObject obj) {
      JsonElement elem = obj.get(JSON_ID);
      if (elem == null || !elem.isJsonPrimitive()) return -1;
      return elem.getAsInt();
    }

    static String extractName(JsonObject obj) {
      JsonElement elem = obj.get(JSON_NAME);
      if (elem == null || elem.isJsonNull()) return NO_NAME;
      return elem.getAsString();
    }

    static Metadata extractMetadata(JsonObject obj) {
      return Metadata.from(obj.get(DEF_METADATA));
    }

    static Suite lookupSuite(JsonObject obj, Map<Integer, Suite> suites) {
      JsonElement suiteObj = obj.get(JSON_SUITE_ID);
      Suite suite = null;
      if (suiteObj != null && suiteObj.isJsonPrimitive()) {
        int parentId = suiteObj.getAsInt();
        suite = suites.get(parentId);
      }
      return suite;
    }

    Item(int id, String name, Group parent, Suite suite, Metadata metadata) {
      myId = id;
      myName = name;
      myParent = parent;
      mySuite = suite;
      myMetadata = metadata;
    }

    int getId() {
      return myId;
    }

    String getName() {
      return myName;
    }

    String getBaseName() {
      if (hasValidParent()) {
        int parentLength = getParent().getName().length();
        if (myName.length() > parentLength) {
          return myName.substring(parentLength + 1);
        }
      }
      return myName;
    }

    boolean hasSuite() {
      return mySuite != null && mySuite.hasPath();
    }

    Suite getSuite() {
      return mySuite;
    }

    Group getParent() {
      return myParent;
    }

    Metadata getMetadata() {
      return myMetadata;
    }

    boolean isArtificial() {
      return myName == NO_NAME;
    }

    boolean hasValidParent() {
      return !(myParent == null || myParent.isArtificial());
    }

    int getValidParentId() {
      if (hasValidParent()) {
        return getParent().getId();
      }
      else {
        return 0;
      }
    }

    List<String> nameList() {
      List<String> names = new ArrayList<String>();
      addNames(names);
      return names;
    }

    void addNames(List<String> names) {
      if (hasValidParent()) {
        myParent.addNames(names);
      }
      names.add(StringUtil.escapeStringCharacters(getBaseName()));
    }

    public String toString() {
      return getClass().getSimpleName() + "(" + String.valueOf(myId) + "," + String.valueOf(myName) + ")";
    }
  }

  private static class Test extends Item {
    private boolean isRunning = true;

    static Test from(JsonObject obj, Map<Integer, Group> groups, Map<Integer, Suite> suites) {
      int[] groupIds = GSON.fromJson(obj.get(JSON_GROUP_IDS), (Type)int[].class);
      Group parent = null;
      if (groupIds != null && groupIds.length > 0) {
        parent = groups.get(groupIds[groupIds.length - 1]);
      }
      Suite suite = lookupSuite(obj, suites);
      return new Test(extractId(obj), extractName(obj), parent, suite, extractMetadata(obj));
    }

    Test(int id, String name, Group parent, Suite suite, Metadata metadata) {
      super(id, name, parent, suite, metadata);
    }
  }

  private static class Group extends Item {
    private int myTestCount = 0;

    static int extractTestCount(JsonObject obj) {
      JsonElement elem = obj.get(JSON_TEST_COUNT);
      if (elem == null || !elem.isJsonPrimitive()) return -1;
      return elem.getAsInt();
    }

    static Group from(JsonObject obj, Map<Integer, Group> groups, Map<Integer, Suite> suites) {
      JsonElement parentObj = obj.get(JSON_PARENT_ID);
      Group parent = null;
      if (parentObj != null && parentObj.isJsonPrimitive()) {
        int parentId = parentObj.getAsInt();
        parent = groups.get(parentId);
      }
      Suite suite = lookupSuite(obj, suites);
      return new Group(extractId(obj), extractName(obj), parent, suite, extractMetadata(obj), extractTestCount(obj));
    }

    Group(int id, String name, Group parent, Suite suite, Metadata metadata, int count) {
      super(id, name, parent, suite, metadata);
      myTestCount = count;
    }

    int getTestCount() {
      return myTestCount;
    }
  }

  private static class Suite extends Item {
    static Metadata NoMetadata = new Metadata();
    static String NONE = "<none>";

    static Suite from(JsonObject obj) {
      return new Suite(extractId(obj), extractPath(obj), extractPlatform(obj));
    }

    static String extractPath(JsonObject obj) {
      JsonElement elem = obj.get(JSON_PATH);
      if (elem == null || elem.isJsonNull()) return NONE;
      return elem.getAsString();
    }

    static String extractPlatform(JsonObject obj) {
      JsonElement elem = obj.get(JSON_PLATFORM);
      if (elem == null || elem.isJsonNull()) return NONE;
      return elem.getAsString();
    }

    private final String myPlatform;

    Suite(int id, String path, String platform) {
      super(id, path, null, null, NoMetadata);
      myPlatform = platform;
    }

    String getPath() {
      return getName();
    }

    String getPlatform() {
      return myPlatform;
    }

    boolean hasPath() {
      return getPath() != NONE;
    }
  }

  private static class Metadata {
    private boolean skip; // GSON needs name in JSON string.
    private String skipReason; // GSON needs name in JSON string.

    static Metadata from(JsonElement elem) {
      if (elem == null) return new Metadata();
      return GSON.fromJson(elem, (Type)Metadata.class);
    }
  }
}
