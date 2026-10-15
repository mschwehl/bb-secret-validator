package io.github.mschwehl.bb.secretvalidator;

import com.atlassian.bitbucket.commit.Commit;
import com.atlassian.bitbucket.commit.CommitRequest;
import com.atlassian.bitbucket.commit.CommitService;
import com.atlassian.bitbucket.commit.MinimalCommit;
import com.atlassian.bitbucket.content.Change;
import com.atlassian.bitbucket.content.ChangeType;
import com.atlassian.bitbucket.content.ChangesRequest;
import com.atlassian.bitbucket.content.ContentService;
import com.atlassian.bitbucket.hook.repository.PreRepositoryHook;
import com.atlassian.bitbucket.hook.repository.PreRepositoryHookContext;
import com.atlassian.bitbucket.hook.repository.RepositoryHookRequest;
import com.atlassian.bitbucket.hook.repository.RepositoryHookResult;
import com.atlassian.bitbucket.idx.CommitIndex;
import com.atlassian.bitbucket.io.TypeAwareOutputSupplier;
import com.atlassian.bitbucket.repository.RefChange;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.util.MoreSuppliers;
import com.atlassian.bitbucket.util.Page;
import com.atlassian.bitbucket.util.PageRequest;
import com.atlassian.bitbucket.util.PageUtils;
import com.hrakaroo.glob.GlobPattern;
import com.hrakaroo.glob.MatchingEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class SecretValidatorPreReceiveRepositoryHook implements PreRepositoryHook {

    private static final Logger LOG = LoggerFactory.getLogger(PreRepositoryHook.class);

    private static final String CONFIG_MERGE_COMMIT_REGEX = "mergeCommitMsgRegex";
    private static final String CONFIG_MERGE_COMMIT_SUMMARY = "mergeCommitMsgSummaryText";
    private static final String CONFIG_MERGE_COMMIT_DETAIL = "mergeCommitMsgDetailedText";

    private static final String SUMMARY = "summary";
    private static final String DETAIL = "detail";

    // \x00 G I T C R Y P T \x00
    byte[] GIT_CRYPT_MAGIC_HEADER = {(byte) 0x00, 'G', 'I', 'T', 'C', 'R', 'Y', 'P', 'T', (byte) 0x00};

    private final CommitService commitService;
    private final ContentService contentService;
    private final CommitIndex commitIndex;

    public SecretValidatorPreReceiveRepositoryHook(final CommitService commitService,
                                                   final ContentService contentService,
                                                   final CommitIndex commitIndex) {
        this.commitService = commitService;
        this.contentService = contentService;
        this.commitIndex = commitIndex;
    }

    @Nonnull
    @Override
    public RepositoryHookResult preUpdate(@Nonnull PreRepositoryHookContext preRepositoryHookContext,
                                          @Nonnull RepositoryHookRequest repositoryHookRequest) {
        String pathPattern = preRepositoryHookContext.getSettings().getString(CONFIG_MERGE_COMMIT_REGEX);
        String summaryHeader = preRepositoryHookContext.getSettings().getString(CONFIG_MERGE_COMMIT_SUMMARY);
        String detailHeader = preRepositoryHookContext.getSettings().getString(CONFIG_MERGE_COMMIT_DETAIL);

        if (pathPattern == null || !pathPattern.contains("*")) {
            return RepositoryHookResult.rejected(
                    summaryHeader + " *MISCONFIGURATION*",
                    detailHeader + " -> Glob pattern must contain '*'");
        }

        Map<String, String> processedResults = onReceive(
                repositoryHookRequest.getRepository(),
                repositoryHookRequest.getRefChanges(),
                pathPattern);

        if (processedResults.containsKey(SUMMARY)) {
            return RepositoryHookResult.rejected(
                    summaryHeader + " -> " + processedResults.get(SUMMARY),
                    detailHeader + " -> " + processedResults.get(DETAIL));
        }
        return RepositoryHookResult.accepted();
    }

    public Map<String, String> onReceive(Repository repository, Collection<RefChange> refChanges, String secretPathRegex) {
        ConcurrentHashMap<String, String> result = new ConcurrentHashMap<>();
        ConcurrentMap<String, Commit> pathChanges = new ConcurrentHashMap<>();

        for (RefChange refChange : refChanges) {
            LOG.debug("Processing refchange of type: {}", refChange.getType());

            Collection<Commit> commitsToCheck = Collections.synchronizedList(new ArrayList<>());
            findCommitsToCheck(refChange.getToHash(), repository, commitsToCheck);

            for (Commit commit : commitsToCheck) {
                addFileChangesOnCommit(pathChanges, repository, commit, secretPathRegex);
            }
        }

        if (!pathChanges.isEmpty()) {
            areFilesValid(pathChanges, repository, result);
        }

        return result;
    }

    private void findCommitsToCheck(String hash, Repository repository, Collection<Commit> commitsToProcess) {
        final String zero = "0000000000000000000000000000000000000000";
        if (zero.equals(hash)) {
            LOG.debug("Found deletion commit");
            return;
        }

        if (!commitIndex.isIndexed(hash, repository)) {
            final CommitRequest request = new CommitRequest.Builder(repository, hash).build();
            final Commit commit = commitService.getCommit(request);
            LOG.debug("Found commit to check {}", hash);
            commitsToProcess.add(commit);
            if (commit != null) {
                for (MinimalCommit parent : commit.getParents()) {
                    findCommitsToCheck(parent.getId(), repository, commitsToProcess);
                }
            }
        }
    }

    private boolean areFilesValid(ConcurrentMap<String, Commit> pathChanges, Repository repository,
                                  ConcurrentMap<String, String> result) {
        LOG.info("Found {} secret files to validate", pathChanges.size());
        boolean allFilesAreValid = true;
        try {
            for (String filePath : pathChanges.keySet()) {
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                TypeAwareOutputSupplier os = MoreSuppliers.newTypeAwareOutputSupplierOf(outputStream);

                contentService.streamFile(
                        repository,
                        pathChanges.get(filePath).getId(),
                        filePath,
                        os);

                try {
                    if (!checkFile(outputStream.toByteArray(), result, filePath)) {
                        allFilesAreValid = false;
                    }
                } finally {
                    outputStream.close();
                }

                if (!allFilesAreValid) {
                    break;
                }
            }
        } catch (IOException e) {
            LOG.error("Error validating secret file: {}", e.getMessage());
        }

        return allFilesAreValid;
    }

    boolean checkFile(byte[] fileBytes, ConcurrentMap<String, String> result, String filePath) {
        boolean validFile = true;

        try {
            LOG.info("Validating secret file: {}", filePath);

            if (fileBytes.length < GIT_CRYPT_MAGIC_HEADER.length) {
                throw new IllegalStateException("File is smaller than git-crypt header");
            }

            for (int i = 0; i < GIT_CRYPT_MAGIC_HEADER.length; i++) {
                if (fileBytes[i] != GIT_CRYPT_MAGIC_HEADER[i]) {
                    throw new IllegalStateException("Git-crypt header mismatch: file is not encrypted");
                }
            }

        } catch (Exception e) {
            LOG.info("Rejecting push because secret file is invalid: {}", filePath);
            result.putIfAbsent(SUMMARY, "ERROR: Invalid secret file: " + filePath);
            result.putIfAbsent(DETAIL, e.getMessage());
            validFile = false;
        }
        return validFile;
    }

    public void addFileChangesOnCommit(ConcurrentMap<String, Commit> filesWithCommits, Repository repository,
                                       Commit commit, String pathPattern) {
        final ChangesRequest changesRequest = new ChangesRequest.Builder(repository, commit.getId()).build();
        final Page<Change> changes = commitService.getChanges(changesRequest, PageUtils.newRequest(0, PageRequest.MAX_PAGE_LIMIT));

        if (changes != null) {
            MatchingEngine m = GlobPattern.compile(pathPattern);

            for (Change change : changes.getValues()) {
                LOG.debug("Change type was: {}", change.getType().name());
                if (!ChangeType.DELETE.equals(change.getType())) {
                    boolean toProcess = false;
                    if (change.getPath() != null) {
                        String path = change.getPath().toString().toLowerCase();
                        if (m.matches(path)) {
                            LOG.info("Secret file matched glob pattern '{}': {}", pathPattern, change.getPath());
                            toProcess = true;
                        } else {
                            LOG.info("File did not match glob pattern '{}', skipping: {}", pathPattern, change.getPath());
                        }
                    }

                    if (toProcess) {
                        if (filesWithCommits.containsKey(change.getPath().toString())) {
                            if (commit.getAuthorTimestamp().after(
                                    filesWithCommits.get(change.getPath().toString()).getAuthorTimestamp())) {
                                filesWithCommits.replace(change.getPath().toString(), commit);
                            }
                        } else {
                            filesWithCommits.putIfAbsent(change.getPath().toString(), commit);
                        }
                    }
                }
            }
        }
    }
}
