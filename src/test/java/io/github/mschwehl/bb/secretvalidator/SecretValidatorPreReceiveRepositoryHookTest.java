package io.github.mschwehl.bb.secretvalidator;

import com.atlassian.bitbucket.commit.Commit;
import com.atlassian.bitbucket.commit.CommitService;
import com.atlassian.bitbucket.content.*;
import com.atlassian.bitbucket.idx.CommitIndex;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.util.Page;
import com.atlassian.bitbucket.util.PageRequest;
import com.atlassian.bitbucket.util.PageUtils;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class SecretValidatorPreReceiveRepositoryHookTest {

    @Test
    public void shouldRejectFileWithoutGitCryptHeader() {
        CommitService commitServiceMock = mock(CommitService.class);
        ContentService contentServiceMock = mock(ContentService.class);
        CommitIndex commitIndexMock = mock(CommitIndex.class);

        SecretValidatorPreReceiveRepositoryHook hook = new SecretValidatorPreReceiveRepositoryHook(
                commitServiceMock, contentServiceMock, commitIndexMock);

        ConcurrentMap<String, String> results = new ConcurrentHashMap<>();
        byte[] plaintext = "this is not encrypted".getBytes();
        boolean check = hook.checkFile(plaintext, results, "secrets/my-secret.key");

        assertFalse(check);
        assertTrue(results.containsKey("summary"));
    }

    @Test
    public void shouldAcceptFileWithGitCryptHeader() {
        CommitService commitServiceMock = mock(CommitService.class);
        ContentService contentServiceMock = mock(ContentService.class);
        CommitIndex commitIndexMock = mock(CommitIndex.class);

        SecretValidatorPreReceiveRepositoryHook hook = new SecretValidatorPreReceiveRepositoryHook(
                commitServiceMock, contentServiceMock, commitIndexMock);

        ConcurrentMap<String, String> results = new ConcurrentHashMap<>();
        byte[] encrypted = new byte[]{0x00, 'G', 'I', 'T', 'C', 'R', 'Y', 'P', 'T', 0x00, 0x01, 0x02};
        boolean check = hook.checkFile(encrypted, results, "secrets/my-secret.key");

        assertTrue(check);
        assertFalse(results.containsKey("summary"));
    }

    @Test
    public void shouldRejectFileShorterThanHeader() {
        CommitService commitServiceMock = mock(CommitService.class);
        ContentService contentServiceMock = mock(ContentService.class);
        CommitIndex commitIndexMock = mock(CommitIndex.class);

        SecretValidatorPreReceiveRepositoryHook hook = new SecretValidatorPreReceiveRepositoryHook(
                commitServiceMock, contentServiceMock, commitIndexMock);

        ConcurrentMap<String, String> results = new ConcurrentHashMap<>();
        byte[] tooShort = new byte[]{0x00, 'G'};
        boolean check = hook.checkFile(tooShort, results, "secrets/tiny.key");

        assertFalse(check);
    }

    @Test
    public void shouldRejectEmptyFile() {
        CommitService commitServiceMock = mock(CommitService.class);
        ContentService contentServiceMock = mock(ContentService.class);
        CommitIndex commitIndexMock = mock(CommitIndex.class);

        SecretValidatorPreReceiveRepositoryHook hook = new SecretValidatorPreReceiveRepositoryHook(
                commitServiceMock, contentServiceMock, commitIndexMock);

        ConcurrentMap<String, String> results = new ConcurrentHashMap<>();
        boolean check = hook.checkFile(new byte[0], results, "secrets/empty.key");

        assertFalse(check);
    }
}
