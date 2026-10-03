package carpet.script.external;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.lang.reflect.Field;
import java.util.ArrayList;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.SystemReport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class TisStackTraceSemanticsTest {
    private boolean previous;
    @BeforeEach void enable() { previous = GeneralCompatConfig.deobfuscateCrashReportStackTrace; GeneralCompatConfig.deobfuscateCrashReportStackTrace = true; }
    @AfterEach void restore() { GeneralCompatConfig.deobfuscateCrashReportStackTrace = previous; }
    private static StackTraceElement frame() { return new StackTraceElement("app-loader", "java.base", "25.0.1", "java.lang.Thread", "run", "Thread.java", 123); }
    private static void field(Object object, String name, Object value) throws Exception { Field f=object.getClass().getDeclaredField(name); f.setAccessible(true); f.set(object,value); }
    private static CrashReport report(Throwable throwable) throws Exception {
        CrashReport report=mock(CrashReport.class, CALLS_REAL_METHODS);
        field(report,"title","test title"); field(report,"exception",throwable);
        field(report,"uncategorizedStackTrace",new StackTraceElement[0]);
        field(report,"details",new ArrayList<CrashReportCategory>()); field(report,"systemReport",mock(SystemReport.class));
        return report;
    }
    @Test void realExceptionFormattingRebuildsCauseChainAndLeavesSuppressedAlone() throws Exception {
        Throwable cause=new IllegalStateException("cause"); cause.setStackTrace(new StackTraceElement[]{frame()});
        Throwable failure=new IllegalArgumentException("failure",cause); failure.setStackTrace(new StackTraceElement[]{frame()});
        Throwable suppressed=new RuntimeException("suppressed"); suppressed.setStackTrace(new StackTraceElement[]{frame()}); failure.addSuppressed(suppressed);
        CrashReport report=report(failure); String result=report.getExceptionMessage();
        assertSame(failure,report.getException()); assertSame(cause,failure.getCause());
        assertNull(failure.getStackTrace()[0].getModuleName()); assertNull(cause.getStackTrace()[0].getClassLoaderName());
        assertEquals("java.lang.Thread.run(Thread.java:123)",failure.getStackTrace()[0].toString());
        assertEquals("java.base",suppressed.getStackTrace()[0].getModuleName());
        assertTrue(result.contains("Caused by: java.lang.IllegalStateException: cause"));
    }
    @Test void messageReplacementOccursBeforeOriginalConversionHook() throws Exception {
        NullPointerException original=new NullPointerException(); original.setStackTrace(new StackTraceElement[]{frame()});
        CrashReport report=report(original); String result=report.getExceptionMessage();
        assertTrue(result.contains("java.lang.NullPointerException: test title"));
        assertTrue(result.contains("at java.lang.Thread.run(Thread.java:123)"));
        assertFalse(result.contains("app-loader/")); assertEquals("java.base",original.getStackTrace()[0].getModuleName());
    }
    @Test void realCategoryFormattingRebuildsAtHeadAndKeepsFrameCoordinates() throws Exception {
        CrashReportCategory category=new CrashReportCategory("category"); StackTraceElement[] original={frame()}; field(category,"stackTrace",original);
        StringBuilder result=new StringBuilder(); category.getDetails(result);
        assertNotSame(original,category.getStacktrace()); assertEquals("java.lang.Thread",category.getStacktrace()[0].getClassName());
        assertEquals(123,category.getStacktrace()[0].getLineNumber()); assertFalse(result.toString().contains("java.base@"));
    }
    @Test void realReportDerivesHeadBeforeItsFirstAppendHook() throws Exception {
        CrashReport report=report(new RuntimeException("failure")); CrashReportCategory category=new CrashReportCategory("category");
        field(category,"stackTrace",new StackTraceElement[]{frame()}); Field details=CrashReport.class.getDeclaredField("details"); details.setAccessible(true);
        ((java.util.List<CrashReportCategory>)details.get(report)).add(category);
        String result=report.getDetails(); assertTrue(result.contains("-- Head --")); assertTrue(result.contains("-- category --"));
        assertFalse(result.contains("app-loader/")); Field head=CrashReport.class.getDeclaredField("uncategorizedStackTrace");head.setAccessible(true);
        assertNull(((StackTraceElement[])head.get(report))[0].getModuleName());
    }
    @Test void disabledRulePreservesOriginalFrameIdentityAndMetadata() throws Exception {
        GeneralCompatConfig.deobfuscateCrashReportStackTrace=false;
        Throwable failure=new RuntimeException("failure"); StackTraceElement original=frame(); failure.setStackTrace(new StackTraceElement[]{original});
        String result=report(failure).getExceptionMessage(); assertSame(original,failure.getStackTrace()[0]); assertTrue(result.contains("app-loader/java.base@25.0.1"));
        CrashReportCategory category=new CrashReportCategory("category"); StackTraceElement[] stack={original};field(category,"stackTrace",stack);
        category.getDetails(new StringBuilder());assertSame(stack,category.getStacktrace());
    }
}
