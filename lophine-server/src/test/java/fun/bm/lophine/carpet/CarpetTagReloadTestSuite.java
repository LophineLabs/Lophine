package fun.bm.lophine.carpet;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectPackages("fun.bm.lophine.carpet")
@ConfigurationParameter(key = "TestSuite", value = "AllFeatures")
public class CarpetTagReloadTestSuite {
}
