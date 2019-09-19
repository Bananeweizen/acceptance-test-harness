/*
 * The MIT License
 *
 * Copyright (c) 2015 Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package plugins;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.jenkinsci.test.acceptance.Matchers.*;
import static org.jenkinsci.test.acceptance.po.FormValidation.Kind.OK;
import static org.junit.Assert.assertEquals;

import javax.inject.Named;

import org.jenkinsci.test.acceptance.junit.AbstractJUnitTest;
import org.jenkinsci.test.acceptance.junit.TestActivation;
import org.jenkinsci.test.acceptance.junit.WithCredentials;
import org.jenkinsci.test.acceptance.junit.WithPlugins;
import org.jenkinsci.test.acceptance.plugins.config_file_provider.ConfigFileProvider;
import org.jenkinsci.test.acceptance.plugins.openstack.OpenstackBuildWrapper;
import org.jenkinsci.test.acceptance.plugins.openstack.OpenstackCloud;
import org.jenkinsci.test.acceptance.plugins.openstack.OpenstackOneOffSlave;
import org.jenkinsci.test.acceptance.plugins.openstack.OpenstackSlaveTemplate;
import org.jenkinsci.test.acceptance.plugins.openstack.UserDataConfig;
import org.jenkinsci.test.acceptance.po.Build;
import org.jenkinsci.test.acceptance.po.FormValidation;
import org.jenkinsci.test.acceptance.po.FreeStyleJob;
import org.jenkinsci.test.acceptance.po.JenkinsConfig;
import org.jenkinsci.test.acceptance.po.MatrixBuild;
import org.jenkinsci.test.acceptance.po.MatrixProject;
import org.jenkinsci.test.acceptance.po.MatrixRun;
import org.jenkinsci.test.acceptance.po.Node;
import org.jenkinsci.test.acceptance.po.Slave;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;

import com.google.inject.Inject;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

@WithPlugins("openstack-cloud")
@TestActivation({"ENDPOINT", "CREDENTIAL"})
public class OpenstackCloudPluginTest extends AbstractJUnitTest {

    private static final String CLOUD_INIT_NAME = "cloudInit";
    private static final String CLOUD_NAME = "OSCloud";
    private static final String CLOUD_DEFAULT_TEMPLATE = "ath-integration-test";
    private static final String MACHINE_USERNAME = "jenkins";
    private static final String SSH_CRED_ID = "ssh-cred-id";
    private static final int PROVISIONING_TIMEOUT = 480;

    @Inject(optional = true) @Named("OpenstackCloudPluginTest.ENDPOINT")
    public String ENDPOINT;

    @Inject(optional = true) @Named("OpenstackCloudPluginTest.IDENTITY")
    @Deprecated public String IDENTITY;

    @Inject(optional = true) @Named("OpenstackCloudPluginTest.USER") public String USER;
    @Inject(optional = true) @Named("OpenstackCloudPluginTest.USER_DOMAIN") public String USER_DOMAIN;
    @Inject(optional = true) @Named("OpenstackCloudPluginTest.PROJECT") public String PROJECT;
    @Inject(optional = true) @Named("OpenstackCloudPluginTest.PROJECT_DOMAIN") public String PROJECT_DOMAIN;

    @Inject(optional = true) @Named("OpenstackCloudPluginTest.CREDENTIAL")
    public String CREDENTIAL;

    @Inject(optional = true) @Named("OpenstackCloudPluginTest.HARDWARE_ID")
    public String HARDWARE_ID;

    @Inject(optional = true) @Named("OpenstackCloudPluginTest.NETWORK_ID")
    public String NETWORK_ID;

    @Inject(optional = true) @Named("OpenstackCloudPluginTest.IMAGE_ID")
    public String IMAGE_ID;

    @Inject(optional = true) @Named("OpenstackCloudPluginTest.KEY_PAIR_NAME")
    public String KEY_PAIR_NAME;

    @Inject(optional = true) @Named("OpenstackCloudPluginTest.FIP_POOL_NAME")
    public String FIP_POOL_NAME;

    @Before
    public void setUp() {
        if ("".equals(USER_DOMAIN)) USER_DOMAIN = null;
        if ("".equals(PROJECT_DOMAIN)) PROJECT_DOMAIN = null;
        assertNull("IDENTITY field is deprecated, Use USER and PROJECT", IDENTITY);
    }

    @After // Terminate all nodes
    public void tearDown() {
        // We have never left the config - no nodes to terminate
        if (getCurrentUrl().endsWith("/configure")) return;
        jenkins.runScript("Jenkins.instance.nodes.each { it.terminate() }");
        sleep(5000);
        String s;
        do {
            s = jenkins.runScript("os = Jenkins.instance.clouds[0]?.openstack; if (os) { os.runningNodes.each { os.destroyServer(it) }; return os.runningNodes.size() }; return 0");
        } while (!"0".equals(s));
    }

    @Test
    public void testConnection() {
        JenkinsConfig config = jenkins.getConfigPage();
        config.configure();
        OpenstackCloud cloud = addCloud(config);
        FormValidation val = cloud.testConnection();
        assertThat(val, FormValidation.reports(OK, startsWith("Connection succeeded!")));
    }

    @Test
    @WithCredentials(credentialType = WithCredentials.SSH_USERNAME_PRIVATE_KEY, values = {MACHINE_USERNAME, "/openstack_plugin/unsafe"}, id = SSH_CRED_ID)
    @TestActivation({"HARDWARE_ID", "IMAGE_ID", "KEY_PAIR_NAME", "NETWORK_ID"})
    public void provisionSshSlave() {
        configureCloudInit("cloud-init");
        configureProvisioning("SSH", "label");

        FreeStyleJob job = jenkins.jobs.create();
        job.configure();
        job.setLabelExpression("label");
        job.save();
        job.scheduleBuild().waitUntilFinished(PROVISIONING_TIMEOUT).shouldSucceed();
    }

    @Test
    @WithCredentials(credentialType = WithCredentials.USERNAME_PASSWORD, values = {MACHINE_USERNAME, "ath"}, id = SSH_CRED_ID)
    @TestActivation({"HARDWARE_ID", "IMAGE_ID", "KEY_PAIR_NAME", "NETWORK_ID"})
    public void provisionSshSlaveWithPasswdAuth() {
        configureCloudInit("cloud-init");
        configureProvisioning("SSH", "label");

        FreeStyleJob job = jenkins.jobs.create();
        job.configure();
        job.setLabelExpression("label");
        job.save();
        job.scheduleBuild().waitUntilFinished(PROVISIONING_TIMEOUT).shouldSucceed();
    }

    @Test
    @WithCredentials(credentialType = WithCredentials.USERNAME_PASSWORD, values = {MACHINE_USERNAME, "ath"}, id = SSH_CRED_ID)
    @TestActivation({"HARDWARE_ID", "IMAGE_ID", "KEY_PAIR_NAME", "NETWORK_ID"})
    public void provisionSshSlaveWithPasswdAuthRetryOnFailedAuth() {
        configureCloudInit("cloud-init-authfix");
        configureProvisioning("SSH", "label");

        FreeStyleJob job = jenkins.jobs.create();
        job.configure();
        job.setLabelExpression("label");
        job.save();
        job.scheduleBuild().waitUntilFinished(PROVISIONING_TIMEOUT).shouldSucceed();
    }

    // The test will fail when test host is not reachable from openstack machine for obvious reasons
    @Test
    // TODO: JENKINS-30784 Do not bother with credentials for jnlp slaves
    @WithCredentials(credentialType = WithCredentials.SSH_USERNAME_PRIVATE_KEY, values = {MACHINE_USERNAME, "/openstack_plugin/unsafe"}, id = SSH_CRED_ID)
    @TestActivation({"HARDWARE_ID", "IMAGE_ID", "KEY_PAIR_NAME", "NETWORK_ID"})
    public void provisionJnlpSlave() {
        configureCloudInit("cloud-init-jnlp");
        configureProvisioning("JNLP", "label");

        FreeStyleJob job = jenkins.jobs.create();
        job.configure();
        job.setLabelExpression("label");
        job.save();
        job.scheduleBuild().waitUntilFinished(PROVISIONING_TIMEOUT).shouldSucceed();
    }

    @Test @Issue("JENKINS-29998")
    @WithCredentials(credentialType = WithCredentials.SSH_USERNAME_PRIVATE_KEY, values = {MACHINE_USERNAME, "/openstack_plugin/unsafe"}, id = SSH_CRED_ID)
    @TestActivation({"HARDWARE_ID", "IMAGE_ID", "KEY_PAIR_NAME", "NETWORK_ID"})
    @WithPlugins("matrix-project")
    public void scheduleMatrixWithoutLabel() {
        configureCloudInit("cloud-init");
        configureProvisioning("SSH", "label");
        jenkins.configure();
        jenkins.getConfigPage().numExecutors.set(0);
        jenkins.save();

        MatrixProject job = jenkins.jobs.create(MatrixProject.class);
        job.configure();
        job.save();

        MatrixBuild pb = job.scheduleBuild().waitUntilFinished(PROVISIONING_TIMEOUT).shouldSucceed().as(MatrixBuild.class);
        assertThat(pb.getNode(), equalTo((Node) jenkins));
        MatrixRun cb = pb.getConfiguration("default");
        assertThat(cb.getNode(), not(equalTo((Node) jenkins)));
    }

    @Test
    @WithCredentials(credentialType = WithCredentials.SSH_USERNAME_PRIVATE_KEY, values = {MACHINE_USERNAME, "/openstack_plugin/unsafe"}, id = SSH_CRED_ID)
    @TestActivation({"HARDWARE_ID", "IMAGE_ID", "KEY_PAIR_NAME", "NETWORK_ID"})
    public void usePerBuildInstance() {
        configureCloudInit("cloud-init");
        configureProvisioning("SSH", "unused");

        FreeStyleJob job = jenkins.jobs.create();
        job.configure();
        OpenstackBuildWrapper bw = job.addBuildWrapper(OpenstackBuildWrapper.class);
        bw.cloud(CLOUD_NAME);
        bw.template(CLOUD_DEFAULT_TEMPLATE);
        bw.count(1);
        // Wait a little for the other machine to start responding
        job.addShellStep("while ! ping -c 1 \"$JCLOUDS_IPS\"; do :; done");
        job.save();

        job.scheduleBuild().waitUntilFinished(PROVISIONING_TIMEOUT).shouldSucceed();
    }

    @Test
    @WithCredentials(credentialType = WithCredentials.SSH_USERNAME_PRIVATE_KEY, values = {MACHINE_USERNAME, "/openstack_plugin/unsafe"}, id = SSH_CRED_ID)
    @TestActivation({"HARDWARE_ID", "IMAGE_ID", "KEY_PAIR_NAME", "NETWORK_ID"})
    public void useSingleUseSlave() {
        configureCloudInit("cloud-init");
        configureProvisioning("SSH", "label");

        FreeStyleJob job = jenkins.jobs.create();
        job.configure();
        job.setLabelExpression("label");
        job.addBuildWrapper(OpenstackOneOffSlave.class);
        job.save();

        Build build = job.scheduleBuild().waitUntilFinished(PROVISIONING_TIMEOUT).shouldSucceed();
        assertTrue(build.getNode().isTemporarillyOffline());
    }

    @Test
    @WithCredentials(credentialType = WithCredentials.USERNAME_PASSWORD, values = {MACHINE_USERNAME, "ath"}, id = SSH_CRED_ID)
    @TestActivation({"HARDWARE_ID", "IMAGE_ID", "KEY_PAIR_NAME", "NETWORK_ID"})
    public void sshSlaveShouldSurviveRestart() {
        assumeTrue("This test requires a restartable Jenkins", jenkins.canRestart());
        configureCloudInit("cloud-init");
        configureProvisioning("SSH", "label");

        FreeStyleJob job = jenkins.jobs.create();
        job.configure();
        job.setLabelExpression("label");
        job.addShellStep("uname -a");
        job.save();
        Node created = job.scheduleBuild().waitUntilFinished(PROVISIONING_TIMEOUT).shouldSucceed().getNode();

        jenkins.restart();

        Node reconnected = job.scheduleBuild().waitUntilFinished(PROVISIONING_TIMEOUT).shouldSucceed().getNode();

        assertEquals(created, reconnected);

        Slave slave = (Slave) reconnected;
        slave.open();
        slave.clickLink("Schedule Termination");
        waitFor(slave, pageObjectDoesNotExist(), 1000);
    }

    private OpenstackCloud addCloud(JenkinsConfig config) {
        return config.addCloud(OpenstackCloud.class)
                .profile(CLOUD_NAME)
                .endpoint(ENDPOINT)
                .credential(USER, USER_DOMAIN, PROJECT, PROJECT_DOMAIN, CREDENTIAL)
        ;
    }

    private void configureCloudInit(String cloudInitName) {
        ConfigFileProvider fileProvider = new ConfigFileProvider(jenkins);
        UserDataConfig cloudInit = fileProvider.addFile(UserDataConfig.class);
        cloudInit.name(CLOUD_INIT_NAME);
        cloudInit.content(resource("/openstack_plugin/" + cloudInitName).asText());
        cloudInit.save();
    }

    private void configureProvisioning(String type, String labels) {
        jenkins.configure();
        OpenstackCloud cloud = addCloud(jenkins.getConfigPage());
        if (FIP_POOL_NAME != null) {
            cloud.associateFloatingIp(FIP_POOL_NAME);
        }
        cloud.instanceCap(3);
        OpenstackSlaveTemplate template = cloud.addSlaveTemplate();

        template.name(CLOUD_DEFAULT_TEMPLATE);
        template.labels(labels);
        template.hardwareId(HARDWARE_ID);
        template.networkId(NETWORK_ID);
        template.imageId(IMAGE_ID);
        template.connectionType(type);
        if ("SSH".equals(type)) {
            template.sshCredentials(SSH_CRED_ID);
        }
        template.userData(CLOUD_INIT_NAME);
        template.keyPair(KEY_PAIR_NAME);
        template.fsRoot("/tmp/jenkins");
        jenkins.save();
    }
}
