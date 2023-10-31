// Copyright (C) 2014 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.acceptance.edit;

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.block;
import static com.google.gerrit.entities.Patch.COMMIT_MSG;
import static com.google.gerrit.extensions.client.ListChangesOption.CURRENT_COMMIT;
import static com.google.gerrit.extensions.client.ListChangesOption.CURRENT_REVISION;
import static com.google.gerrit.extensions.client.ListChangesOption.DETAILED_LABELS;
import static com.google.gerrit.extensions.client.ListChangesOption.MESSAGES;
import static com.google.gerrit.extensions.common.testing.EditInfoSubject.assertThat;
import static com.google.gerrit.extensions.restapi.testing.BinaryResultSubject.assertThat;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.TestProjectInput;
import com.google.gerrit.acceptance.UseClockStep;
import com.google.gerrit.acceptance.testsuite.account.AccountOperations;
import com.google.gerrit.acceptance.testsuite.change.ChangeOperations;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.acceptance.testsuite.request.RequestScopeOperations;
import com.google.gerrit.common.RawInputUtil;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.LabelId;
import com.google.gerrit.entities.LabelType;
import com.google.gerrit.entities.Patch;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.entities.Permission;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.api.changes.ChangeEditIdentityType;
import com.google.gerrit.extensions.api.changes.FileContentInput;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.api.changes.PublishChangeEditInput;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.ReviewerInput;
import com.google.gerrit.extensions.client.ChangeEditDetailOption;
import com.google.gerrit.extensions.client.ChangeKind;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gerrit.extensions.client.ListChangesOption;
import com.google.gerrit.extensions.common.ApprovalInfo;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.ChangeInput;
import com.google.gerrit.extensions.common.CommitInfo;
import com.google.gerrit.extensions.common.DiffInfo;
import com.google.gerrit.extensions.common.EditInfo;
import com.google.gerrit.extensions.common.FileInfo;
import com.google.gerrit.extensions.common.GitPerson;
import com.google.gerrit.extensions.common.RevisionInfo;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gerrit.extensions.restapi.RawInput;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.server.ChangeMessagesUtil;
import com.google.gerrit.server.project.testing.TestLabels;
import com.google.gerrit.server.restapi.change.ChangeEdits;
import com.google.gerrit.server.restapi.change.ChangeEdits.EditMessage;
import com.google.gerrit.server.restapi.change.ChangeEdits.Post;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.inject.Inject;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.Before;
import org.junit.Test;

@UseClockStep
public class ChangeEditIT extends AbstractDaemonTest {

  private static final String FILE_NAME = "foo";
  private static final String FILE_NAME2 = "foo2";
  private static final String FILE_NAME3 = "foo3";
  private static final int FILE_MODE = 100644;
  private static final byte[] CONTENT_OLD = "bar".getBytes(UTF_8);
  private static final byte[] CONTENT_NEW = "baz".getBytes(UTF_8);
  private static final String CONTENT_NEW2_STR = "quxÄÜÖßµ";
  private static final byte[] CONTENT_NEW2 = CONTENT_NEW2_STR.getBytes(UTF_8);
  private static final String CONTENT_BINARY_ENCODED_NEW =
      "data:text/plain;base64,SGVsbG8sIFdvcmxkIQ==";
  private static final byte[] CONTENT_BINARY_DECODED_NEW = "Hello, World!".getBytes(UTF_8);
  private static final String CONTENT_BINARY_ENCODED_NEW2 =
      "data:text/plain;base64,VXBsb2FkaW5nIHRvIGFuIGVkaXQgd29ya2VkIQ==";
  private static final byte[] CONTENT_BINARY_DECODED_NEW2 =
      "Uploading to an edit worked!".getBytes(UTF_8);
  private static final String CONTENT_BINARY_ENCODED_NEW3 =
      "data:text/plain,VXBsb2FkaW5nIHRvIGFuIGVkaXQgd29ya2VkIQ==";
  private static final String CONTENT_BINARY_ENCODED_EMPTY = "data:text/plain;base64,";

  @Inject private ProjectOperations projectOperations;
  @Inject private RequestScopeOperations requestScopeOperations;
  @Inject private AccountOperations accountOperations;
  @Inject private ChangeOperations changeOperations;

  private String changeId;
  private String changeId2;
  private PatchSet ps;

  @Before
  public void setUp() throws Exception {
    changeId = newChange(admin.newIdent());
    ps = getCurrentPatchSet(changeId);
    assertThat(ps).isNotNull();
    addNewPatchSet(changeId);
    changeId2 = newChange2(admin.newIdent());
  }

  @Test
  public void parseEditRevision() throws Exception {
    createArbitraryEditFor(changeId);

    // check that '0' is parsed as edit revision
    gApi.changes().id(changeId).revision(0).comments();

    // check that 'edit' is parsed as edit revision
    gApi.changes().id(changeId).revision("edit").comments();
  }

  @Test
  public void deleteEditOfCurrentPatchSet() throws Exception {
    createArbitraryEditFor(changeId);
    gApi.changes().id(changeId).edit().delete();
    assertThat(getEdit(changeId)).isAbsent();
  }

  @Test
  public void deleteEditOfOlderPatchSet() throws Exception {
    createArbitraryEditFor(changeId2);
    addNewPatchSet(changeId2);

    gApi.changes().id(changeId2).edit().delete();
    assertThat(getEdit(changeId2)).isAbsent();
  }

  @Test
  public void publishEdit() throws Exception {
    createArbitraryEditFor(changeId);

    ReviewerInput in = new ReviewerInput();
    in.reviewer = user.email();
    gApi.changes().id(changeId).addReviewer(in);

    PublishChangeEditInput publishInput = new PublishChangeEditInput();
    gApi.changes().id(changeId).edit().publish(publishInput);

    assertThat(getEdit(changeId)).isAbsent();
    assertChangeMessages(
        changeId,
        ImmutableList.of(
            "Uploaded patch set 1.",
            "Uploaded patch set 2.",
            "Patch Set 3: Published edit on patch set 2."));

    // The tag for the publish edit change message should vary according
    // to whether the change was WIP at the time of publishing.
    ChangeInfo info = get(changeId, MESSAGES);
    assertThat(info.messages).isNotEmpty();
    assertThat(Iterables.getLast(info.messages).tag)
        .isEqualTo(ChangeMessagesUtil.TAG_UPLOADED_PATCH_SET);
    assertThat(sender.getMessages()).isNotEmpty();

    // Move the change to WIP, repeat, and verify.
    sender.clear();
    gApi.changes().id(changeId).setWorkInProgress();
    createEmptyEditFor(changeId);
    gApi.changes().id(changeId).edit().modifyFile(FILE_NAME, RawInputUtil.create(CONTENT_NEW2));
    gApi.changes().id(changeId).edit().publish();
    info = get(changeId, MESSAGES);
    assertThat(info.messages).isNotEmpty();
    assertThat(Iterables.getLast(info.messages).tag)
        .isEqualTo(ChangeMessagesUtil.TAG_UPLOADED_WIP_PATCH_SET);
    assertThat(sender.getMessages()).isEmpty();
  }

  @Test
  public void publishEditRest() throws Exception {
    PatchSet oldCurrentPatchSet = getCurrentPatchSet(changeId);
    createArbitraryEditFor(changeId);

    adminRestSession.post(urlPublish(changeId)).assertNoContent();
    assertThat(getEdit(changeId)).isAbsent();
    PatchSet newCurrentPatchSet = getCurrentPatchSet(changeId);
    assertThat(newCurrentPatchSet.id()).isNotEqualTo(oldCurrentPatchSet.id());
    assertChangeMessages(
        changeId,
        ImmutableList.of(
            "Uploaded patch set 1.",
            "Uploaded patch set 2.",
            "Patch Set 3: Published edit on patch set 2."));
  }

  @Test
  public void publishEditNotifyRest() throws Exception {
    ReviewerInput in = new ReviewerInput();
    in.reviewer = user.email();
    gApi.changes().id(changeId).addReviewer(in);

    createArbitraryEditFor(changeId);

    sender.clear();
    PublishChangeEditInput input = new PublishChangeEditInput();
    input.notify = NotifyHandling.NONE;
    adminRestSession.post(urlPublish(changeId), input).assertNoContent();
    assertThat(sender.getMessages()).isEmpty();
  }

  @Test
  public void publishEditWithDefaultNotify() throws Exception {
    ReviewerInput in = new ReviewerInput();
    in.reviewer = user.email();
    gApi.changes().id(changeId).addReviewer(in);

    createArbitraryEditFor(changeId);

    sender.clear();
    gApi.changes().id(changeId).edit().publish();
    assertThat(sender.getMessages()).isNotEmpty();
  }

  @Test
  public void deleteEditRest() throws Exception {
    createArbitraryEditFor(changeId);
    adminRestSession.delete(urlEdit(changeId)).assertNoContent();
    assertThat(getEdit(changeId)).isAbsent();
  }

  @Test
  public void rebaseEdit() throws Exception {
    PatchSet previousPatchSet = getCurrentPatchSet(changeId2);
    createEmptyEditFor(changeId2);
    gApi.changes().id(changeId2).edit().modifyFile(FILE_NAME, RawInputUtil.create(CONTENT_NEW));
    addNewPatchSet(changeId2);
    PatchSet currentPatchSet = getCurrentPatchSet(changeId2);

    Optional<EditInfo> originalEdit = getEdit(changeId2);
    assertThat(originalEdit).value().baseRevision().isEqualTo(previousPatchSet.commitId().name());
    Timestamp beforeRebase = originalEdit.get().commit.committer.date;
    gApi.changes().id(changeId2).edit().rebase();
    ensureSameBytes(getFileContentOfEdit(changeId2, FILE_NAME), CONTENT_NEW);
    ensureSameBytes(getFileContentOfEdit(changeId2, FILE_NAME2), CONTENT_NEW2);
    Optional<EditInfo> rebasedEdit = getEdit(changeId2);
    assertThat(rebasedEdit).value().baseRevision().isEqualTo(currentPatchSet.commitId().name());
    assertThat(rebasedEdit).value().commit().committer().date().isNotEqualTo(beforeRebase);
  }

  @Test
  public void rebaseEditAfterUpdatingPreferredEmail() throws Exception {
    String emailOne = "email1@example.com";
    Account.Id testUser = accountOperations.newAccount().preferredEmail(emailOne).create();

    // Create change to edit
    Change.Id change = changeOperations.newChange().project(project).owner(testUser).create();

    // Change preferred email for the user
    String emailTwo = "email2@example.com";
    accountOperations.account(testUser).forUpdate().preferredEmail(emailTwo).update();
    requestScopeOperations.setApiUser(testUser);

    // Create edit
    createEmptyEditFor(project + "~" + change.get());
    // Add new patch-set to change
    changeOperations.change(change).newPatchset().create();
    // Rebase Edit
    gApi.changes().id(change.get()).edit().rebase();

    EditInfo editInfo = gApi.changes().id(change.get()).edit().get().orElseThrow();
    assertThat(editInfo.commit.committer.email).isEqualTo(emailOne);
  }

  @Test
  public void rebaseEditRest() throws Exception {
    PatchSet previousPatchSet = getCurrentPatchSet(changeId2);
    createEmptyEditFor(changeId2);
    gApi.changes().id(changeId2).edit().modifyFile(FILE_NAME, RawInputUtil.create(CONTENT_NEW));
    addNewPatchSet(changeId2);
    PatchSet currentPatchSet = getCurrentPatchSet(changeId2);

    Optional<EditInfo> originalEdit = getEdit(changeId2);
    assertThat(originalEdit).value().baseRevision().isEqualTo(previousPatchSet.commitId().name());
    Timestamp beforeRebase = originalEdit.get().commit.committer.date;
    adminRestSession.post(urlRebase(changeId2)).assertNoContent();
    ensureSameBytes(getFileContentOfEdit(changeId2, FILE_NAME), CONTENT_NEW);
    ensureSameBytes(getFileContentOfEdit(changeId2, FILE_NAME2), CONTENT_NEW2);
    Optional<EditInfo> rebasedEdit = getEdit(changeId2);
    assertThat(rebasedEdit).value().baseRevision().isEqualTo(currentPatchSet.commitId().name());
    assertThat(rebasedEdit).value().commit().committer().date().isNotEqualTo(beforeRebase);
  }

  @Test
  public void rebaseEditWithConflictsRest_conflict() throws Exception {
    PatchSet currentPatchSet = getCurrentPatchSet(changeId2);
    createEmptyEditFor(changeId2);
    gApi.changes().id(changeId2).edit().modifyFile(FILE_NAME, RawInputUtil.create(CONTENT_NEW));
    Optional<EditInfo> edit = getEdit(changeId2);
    assertThat(edit).value().baseRevision().isEqualTo(currentPatchSet.commitId().name());
    PushOneCommit push =
        pushFactory.create(
            admin.newIdent(),
            testRepo,
            PushOneCommit.SUBJECT,
            FILE_NAME,
            new String(CONTENT_NEW2, UTF_8),
            changeId2);
    push.to("refs/for/master").assertOkStatus();
    adminRestSession.post(urlRebase(changeId2)).assertConflict();
  }

  @Test
  public void updateExistingFile() throws Exception {
    createEmptyEditFor(changeId);
    gApi.changes().id(changeId).edit().modifyFile(FILE_NAME, RawInputUtil.create(CONTENT_NEW));
    assertThat(getEdit(changeId)).isPresent();
    ensureSameBytes(getFileContentOfEdit(changeId, FILE_NAME), CONTENT_NEW);
    ensureSameBytes(getFileContentOfEdit(changeId, FILE_NAME), CONTENT_NEW);
  }

  @Test
  public void updateExistingFileAfterUpdatingPreferredEmail() throws Exception {
    String emailOne = "email1@example.com";
    Account.Id testUser = accountOperations.newAccount().preferredEmail(emailOne).create();

    // Create change to edit
    Change.Id change =
        changeOperations
            .newChange()
            .project(project)
            .file(FILE_NAME)
            .content("content")
            .owner(testUser)
            .create();

    // Change preferred email for the user
    String emailTwo = "email2@example.com";
    accountOperations.account(testUser).forUpdate().preferredEmail(emailTwo).update();
    requestScopeOperations.setApiUser(testUser);

    // Modify file
    gApi.changes().id(change.get()).edit().modifyFile(FILE_NAME, RawInputUtil.create(CONTENT_NEW));

    EditInfo editInfo = gApi.changes().id(change.get()).edit().get().orElseThrow();
    assertThat(editInfo.commit.committer.email).isEqualTo(emailOne);
  }

  @Test
  public void updateCommitMessageByEditingMagicCommitMsgFile() throws Exception {
    createEmptyEditFor(changeId);
    String updatedCommitMsg = "Foo Bar\n\nChange-Id: " + changeId + "\n";
    gApi.changes()
        .id(changeId)
        .edit()
        .modifyFile(Patch.COMMIT_MSG, RawInputUtil.create(updatedCommitMsg.getBytes(UTF_8)));
    assertThat(getEdit(changeId)).isPresent();
    ensureSameBytes(
        getFileContentOfEdit(changeId, Patch.COMMIT_MSG), updatedCommitMsg.getBytes(UTF_8));
  }

  @Test
  public void updateCommitMessageByEditingMagicCommitMsgFileChangingChangeIdFooterToLinkFooter()
      throws Exception {
    createEmptyEditFor(changeId);
    String updatedCommitMsg =
        "Foo Bar\n\n\n\nLink: " + canonicalWebUrl.get() + "id/" + changeId + "\n";
    gApi.changes()
        .id(changeId)
        .edit()
        .modifyFile(Patch.COMMIT_MSG, RawInputUtil.create(updatedCommitMsg.getBytes(UTF_8)));
    assertThat(getEdit(changeId)).isPresent();
    ensureSameBytes(
        getFileContentOfEdit(changeId, Patch.COMMIT_MSG), updatedCommitMsg.getBytes(UTF_8));
  }

  @Test
  public void updateCommitMessageByEditingMagicCommitMsgFileWithoutContent() throws Exception {
    createEmptyEditFor(changeId);
    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () -> gApi.changes().id(changeId).edit().modifyFile(Patch.COMMIT_MSG, (RawInput) null));
    assertThat(ex).hasMessageThat().isEqualTo("either content or binary_content is required");
  }

  @Test
  @TestProjectInput(createEmptyCommit = false)
  public void updateRootCommitMessage() throws Exception {
    // Re-clone empty repo; TestRepository doesn't let us reset to unborn head.
    testRepo = cloneProject(project);
    changeId = newChange(admin.newIdent());

    createEmptyEditFor(changeId);
    Optional<EditInfo> edit = getEdit(changeId);
    assertThat(edit).value().commit().parents().isEmpty();

    String msg = String.format("New commit message\n\nChange-Id: %s\n", changeId);
    gApi.changes().id(changeId).edit().modifyCommitMessage(msg);
    String commitMessage = gApi.changes().id(changeId).edit().getCommitMessage();
    assertThat(commitMessage).isEqualTo(msg);
  }

  @Test
  public void updateMessageEditChangeIdShouldThrowResourceConflictException() throws Exception {
    createEmptyEditFor(changeId);
    String commitMessage = gApi.changes().id(changeId).edit().getCommitMessage();

    ResourceConflictException thrown =
        assertThrows(
            ResourceConflictException.class,
            () ->
                gApi.changes()
                    .id(changeId)
                    .edit()
                    .modifyCommitMessage(commitMessage.replaceAll(changeId, changeId2)));
    assertThat(thrown).hasMessageThat().isEqualTo("wrong Change-Id footer");
  }

  @Test
  public void updateMessageEditRemoveChangeIdShouldThrowResourceConflictException()
      throws Exception {
    createEmptyEditFor(changeId);
    String commitMessage = gApi.changes().id(changeId).edit().getCommitMessage();

    ResourceConflictException thrown =
        assertThrows(
            ResourceConflictException.class,
            () ->
                gApi.changes()
                    .id(changeId)
                    .edit()
                    .modifyCommitMessage(commitMessage.replaceAll("(Change-Id:).*", "")));
    assertThat(thrown).hasMessageThat().isEqualTo("missing Change-Id footer");
  }

  @Test
  public void updateMessageNoChange() throws Exception {
    createEmptyEditFor(changeId);
    String commitMessage = gApi.changes().id(changeId).edit().getCommitMessage();

    ResourceConflictException thrown =
        assertThrows(
            ResourceConflictException.class,
            () -> gApi.changes().id(changeId).edit().modifyCommitMessage(commitMessage));
    assertThat(thrown)
        .hasMessageThat()
        .contains("New commit message cannot be same as existing commit message");
  }

  @Test
  public void updateMessageOnlyAddTrailingNewLines() throws Exception {
    createEmptyEditFor(changeId);
    String commitMessage = gApi.changes().id(changeId).edit().getCommitMessage();

    ResourceConflictException thrown =
        assertThrows(
            ResourceConflictException.class,
            () -> gApi.changes().id(changeId).edit().modifyCommitMessage(commitMessage + "\n\n"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("New commit message cannot be same as existing commit message");
  }

  @Test
  public void updateCommitter() throws Exception {
    createEmptyEditFor(changeId);
    gApi.changes()
        .id(changeId)
        .edit()
        .modifyIdentity("Test", "test@example.com", ChangeEditIdentityType.COMMITTER);

    PublishChangeEditInput publishInput = new PublishChangeEditInput();
    publishInput.notify = NotifyHandling.NONE;
    gApi.changes().id(changeId).edit().publish(publishInput);
    assertThat(getEdit(changeId)).isAbsent();

    ChangeInfo info =
        get(changeId, ListChangesOption.CURRENT_COMMIT, ListChangesOption.CURRENT_REVISION);
    GitPerson committer = info.revisions.get(info.currentRevision).commit.committer;
    assertThat(committer.name).isEqualTo("Test");
    assertThat(committer.email).isEqualTo("test@example.com");
  }

  @Test
  public void updateCommitterRest() throws Exception {
    adminRestSession.get(urlEditIdentity(changeId)).assertNotFound();
    ChangeEdits.EditIdentity.Input in = new ChangeEdits.EditIdentity.Input();
    in.name = "Test";
    in.email = "test@example.com";
    in.type = ChangeEditIdentityType.COMMITTER;
    adminRestSession.put(urlEditIdentity(changeId), in).assertNoContent();
    adminRestSession.getJsonAccept(urlEditIdentity(changeId));
    adminRestSession.post(urlPublish(changeId)).assertNoContent();
    ChangeInfo info =
        get(changeId, ListChangesOption.CURRENT_COMMIT, ListChangesOption.CURRENT_REVISION);
    GitPerson committer = info.revisions.get(info.currentRevision).commit.committer;
    assertThat(committer.name).isEqualTo("Test");
    assertThat(committer.email).isEqualTo("test@example.com");
  }

  @Test
  public void updateCommitterRestWithDefaultName() throws Exception {
    adminRestSession.get(urlEditIdentity(changeId)).assertNotFound();
    ChangeEdits.EditIdentity.Input in = new ChangeEdits.EditIdentity.Input();
    in.type = ChangeEditIdentityType.COMMITTER;
    in.email = "test@example.com";
    adminRestSession.put(urlEditIdentity(changeId), in).assertNoContent();
    adminRestSession.getJsonAccept(urlEditIdentity(changeId));
    adminRestSession.post(urlPublish(changeId)).assertNoContent();
    ChangeInfo info =
        get(changeId, ListChangesOption.CURRENT_COMMIT, ListChangesOption.CURRENT_REVISION);
    GitPerson committer = info.revisions.get(info.currentRevision).commit.committer;
    assertThat(committer.name).isEqualTo("Administrator");
    assertThat(committer.email).isEqualTo("test@example.com");
  }

  @Test
  public void updateCommitterRestWithDefaultEmail() throws Exception {
    adminRestSession.get(urlEditIdentity(changeId)).assertNotFound();
    ChangeEdits.EditIdentity.Input in = new ChangeEdits.EditIdentity.Input();
    in.type = ChangeEditIdentityType.COMMITTER;
    in.name = "John Doe";
    adminRestSession.put(urlEditIdentity(changeId), in).assertNoContent();
    adminRestSession.getJsonAccept(urlEditIdentity(changeId));
    adminRestSession.post(urlPublish(changeId)).assertNoContent();
    ChangeInfo info =
        get(changeId, ListChangesOption.CURRENT_COMMIT, ListChangesOption.CURRENT_REVISION);
    GitPerson committer = info.revisions.get(info.currentRevision).commit.committer;
    assertThat(committer.name).isEqualTo("John Doe");
    assertThat(committer.email).isEqualTo("admin@example.com");
  }

  @Test
  public void cannotForgeCommitterWithoutPerm() throws Exception {
    projectOperations
        .project(project)
        .forUpdate()
        .add(block(Permission.FORGE_COMMITTER).ref("refs/*").group(REGISTERED_USERS))
        .update();
    createEmptyEditFor(changeId);
    assertThrows(
        ResourceConflictException.class,
        () ->
            gApi.changes()
                .id(changeId)
                .edit()
                .modifyIdentity("Test", "test@example.com", ChangeEditIdentityType.COMMITTER));
  }

  @Test
  public void updateAuthor() throws Exception {
    createEmptyEditFor(changeId);
    gApi.changes()
        .id(changeId)
        .edit()
        .modifyIdentity("Test", "test@example.com", ChangeEditIdentityType.AUTHOR);

    PublishChangeEditInput publishInput = new PublishChangeEditInput();
    publishInput.notify = NotifyHandling.NONE;
    gApi.changes().id(changeId).edit().publish(publishInput);
    assertThat(getEdit(changeId)).isAbsent();

    ChangeInfo info =
        get(changeId, ListChangesOption.CURRENT_COMMIT, ListChangesOption.CURRENT_REVISION);
    GitPerson author = info.revisions.get(info.currentRevision).commit.author;
    assertThat(author.name).isEqualTo("Test");
    assertThat(author.email).isEqualTo("test@example.com");
  }

  @Test
  public void updateAuthorRest() throws Exception {
    adminRestSession.get(urlEditIdentity(changeId)).assertNotFound();
    ChangeEdits.EditIdentity.Input in = new ChangeEdits.EditIdentity.Input();
    in.name = "Test";
    in.type = ChangeEditIdentityType.AUTHOR;
    in.email = "test@example.com";
    adminRestSession.put(urlEditIdentity(changeId), in).assertNoContent();
    adminRestSession.getJsonAccept(urlEditIdentity(changeId));
    adminRestSession.post(urlPublish(changeId)).assertNoContent();
    ChangeInfo info =
        get(changeId, ListChangesOption.CURRENT_COMMIT, ListChangesOption.CURRENT_REVISION);
    GitPerson author = info.revisions.get(info.currentRevision).commit.author;
    assertThat(author.name).isEqualTo("Test");
    assertThat(author.email).isEqualTo("test@example.com");
  }

  @Test
  public void updateAuthorRestWithDefaultName() throws Exception {
    adminRestSession.get(urlEditIdentity(changeId)).assertNotFound();
    ChangeEdits.EditIdentity.Input in = new ChangeEdits.EditIdentity.Input();
    in.type = ChangeEditIdentityType.AUTHOR;
    in.email = "test@example.com";
    adminRestSession.put(urlEditIdentity(changeId), in).assertNoContent();
    adminRestSession.getJsonAccept(urlEditIdentity(changeId));
    adminRestSession.post(urlPublish(changeId)).assertNoContent();
    ChangeInfo info =
        get(changeId, ListChangesOption.CURRENT_COMMIT, ListChangesOption.CURRENT_REVISION);
    GitPerson author = info.revisions.get(info.currentRevision).commit.author;
    assertThat(author.name).isEqualTo("Administrator");
    assertThat(author.email).isEqualTo("test@example.com");
  }

  @Test
  public void updateAuthorRestWithDefaultEmail() throws Exception {
    adminRestSession.get(urlEditIdentity(changeId)).assertNotFound();
    ChangeEdits.EditIdentity.Input in = new ChangeEdits.EditIdentity.Input();
    in.type = ChangeEditIdentityType.AUTHOR;
    in.name = "John Doe";
    adminRestSession.put(urlEditIdentity(changeId), in).assertNoContent();
    adminRestSession.getJsonAccept(urlEditIdentity(changeId));
    adminRestSession.post(urlPublish(changeId)).assertNoContent();
    ChangeInfo info =
        get(changeId, ListChangesOption.CURRENT_COMMIT, ListChangesOption.CURRENT_REVISION);
    GitPerson author = info.revisions.get(info.currentRevision).commit.author;
    assertThat(author.name).isEqualTo("John Doe");
    assertThat(author.email).isEqualTo("admin@example.com");
  }

  @Test
  public void cannotForgeAuthorWithoutPerm() throws Exception {
    projectOperations
        .project(project)
        .forUpdate()
        .add(block(Permission.FORGE_AUTHOR).ref("refs/*").group(REGISTERED_USERS))
        .update();
    createEmptyEditFor(changeId);
    assertThrows(
        ResourceConflictException.class,
        () ->
            gApi.changes()
                .id(changeId)
                .edit()
                .modifyIdentity("Test", "test@example.com", ChangeEditIdentityType.AUTHOR));
  }

  @Test
  public void updateAuthorAsOtherUser() throws Exception {
    Account.Id otherUser =
        accountOperations
            .newAccount()
            .preferredEmail("otherUser@example.com")
            .fullname("otherUser")
            .create();
    requestScopeOperations.setApiUser(otherUser);
    createEmptyEditFor(changeId);
    gApi.changes()
        .id(changeId)
        .edit()
        .modifyIdentity("Test", "test@example.com", ChangeEditIdentityType.AUTHOR);

    PublishChangeEditInput publishInput = new PublishChangeEditInput();
    publishInput.notify = NotifyHandling.NONE;
    gApi.changes().id(changeId).edit().publish(publishInput);
    assertThat(getEdit(changeId)).isAbsent();

    ChangeInfo info =
        get(changeId, ListChangesOption.CURRENT_COMMIT, ListChangesOption.CURRENT_REVISION);
    GitPerson author = info.revisions.get(info.currentRevision).commit.author;
    GitPerson committer = info.revisions.get(info.currentRevision).commit.committer;
    assertThat(author.name).isEqualTo("Test");
    assertThat(author.email).isEqualTo("test@example.com");
    assertThat(committer.name).isEqualTo("otherUser");
    assertThat(committer.email).isEqualTo("otherUser@example.com");
  }

  @Test
  public void updateMessage() throws Exception {
    createEmptyEditFor(changeId);
    String msg = String.format("New commit message\n\nChange-Id: %s\n", changeId);
    gApi.changes().id(changeId).edit().modifyCommitMessage(msg);
    String commitMessage = gApi.changes().id(changeId).edit().getCommitMessage();
    assertThat(commitMessage).isEqualTo(msg);

    PublishChangeEditInput publishInput = new PublishChangeEditInput();
    publishInput.notify = NotifyHandling.NONE;
    gApi.changes().id(changeId).edit().publish(publishInput);
    assertThat(getEdit(changeId)).isAbsent();

    ChangeInfo info =
        get(changeId, ListChangesOption.CURRENT_COMMIT, ListChangesOption.CURRENT_REVISION);
    assertThat(info.revisions.get(info.currentRevision).commit.message).isEqualTo(msg);
    assertThat(info.revisions.get(info.currentRevision).description)
        .isEqualTo("Edit commit message");

    assertChangeMessages(
        changeId,
        ImmutableList.of(
            "Uploaded patch set 1.",
            "Uploaded patch set 2.",
            "Patch Set 3: Commit message was updated."));
  }

  @Test
  public void updateMessageAfterUpdatingPreferredEmail() throws Exception {
    String emailOne = "email1@example.com";
    Account.Id testUser = accountOperations.newAccount().preferredEmail(emailOne).create();

    // Create change to edit
    Change.Id change = changeOperations.newChange().project(project).owner(testUser).create();

    // Change preferred email for the user
    String emailTwo = "email2@example.com";
    accountOperations.account(testUser).forUpdate().preferredEmail(emailTwo).update();
    requestScopeOperations.setApiUser(testUser);

    // Update commit message
    ChangeInfo changeInfo = gApi.changes().id(change.get()).get();
    String msg = String.format("New commit message\n\nChange-Id: %s\n", changeInfo.changeId);
    gApi.changes().id(change.get()).edit().modifyCommitMessage(msg);

    EditInfo editInfo = gApi.changes().id(change.get()).edit().get().orElseThrow();
    assertThat(editInfo.commit.committer.email).isEqualTo(emailOne);
  }

  @Test
  public void updateMessageRest() throws Exception {
    adminRestSession.get(urlEditMessage(changeId, false)).assertNotFound();
    EditMessage.Input in = new EditMessage.Input();
    in.message =
        String.format(
            "New commit message\n\n" + CONTENT_NEW2_STR + "\n\nChange-Id: %s\n", changeId);
    adminRestSession.put(urlEditMessage(changeId, false), in).assertNoContent();
    RestResponse r = adminRestSession.getJsonAccept(urlEditMessage(changeId, false));
    r.assertOK();
    assertThat(readContentFromJson(r)).isEqualTo(in.message);
    String commitMessage = gApi.changes().id(changeId).edit().getCommitMessage();
    assertThat(commitMessage).isEqualTo(in.message);
    in.message = String.format("New commit message2\n\nChange-Id: %s\n", changeId);
    adminRestSession.put(urlEditMessage(changeId, false), in).assertNoContent();
    String updatedCommitMessage = gApi.changes().id(changeId).edit().getCommitMessage();
    assertThat(updatedCommitMessage).isEqualTo(in.message);

    r = adminRestSession.getJsonAccept(urlEditMessage(changeId, true));
    try (Repository repo = repoManager.openRepository(project);
        RevWalk rw = new RevWalk(repo)) {
      RevCommit commit = rw.parseCommit(ObjectId.fromString(ps.commitId().name()));
      assertThat(readContentFromJson(r)).isEqualTo(commit.getFullMessage());
    }

    PublishChangeEditInput publishInput = new PublishChangeEditInput();
    publishInput.notify = NotifyHandling.NONE;
    gApi.changes().id(changeId).edit().publish(publishInput);
    assertChangeMessages(
        changeId,
        ImmutableList.of(
            "Uploaded patch set 1.",
            "Uploaded patch set 2.",
            "Patch Set 3: Commit message was updated."));
  }

  @Test
  public void retrieveEdit() throws Exception {
    adminRestSession.get(urlEdit(changeId)).assertNoContent();
    createArbitraryEditFor(changeId);
    Optional<EditInfo> maybeEditInfo = gApi.changes().id(changeId).edit().get();
    assertThat(maybeEditInfo).isPresent();
    EditInfo editInfo = maybeEditInfo.get();
    ChangeInfo changeInfo = get(changeId, CURRENT_REVISION, CURRENT_COMMIT);
    assertThat(editInfo.commit.commit).isNotEqualTo(changeInfo.currentRevision);
    assertThat(editInfo).commit().parents().hasSize(1);
    assertThat(editInfo).baseRevision().isEqualTo(changeInfo.currentRevision);

    gApi.changes().id(changeId).edit().delete();

    adminRestSession.get(urlEdit(changeId)).assertNoContent();
  }

  @Test
  public void editIsDiffedAgainstPatchSetParentByDefault() throws Exception {
    // Create a patch set. The previous patch set contained FILE_NAME.
    addNewPatchSetWithModifiedFile(
        changeId2, "file_in_latest_patch_set.txt", "Content of a file in latest patch set.");

    // Create an empty edit on top of that patch set.
    createEmptyEditFor(changeId2);

    Optional<EditInfo> edit =
        gApi.changes()
            .id(changeId2)
            .edit()
            .detail()
            .withOption(ChangeEditDetailOption.LIST_FILES)
            .get();

    assertThat(edit)
        .value()
        .files()
        .keys()
        .containsExactly(COMMIT_MSG, FILE_NAME, "file_in_latest_patch_set.txt");
  }

  @Test
  public void editCanBeDiffedAgainstCurrentPatchSet() throws Exception {
    // Create a patch set.
    addNewPatchSetWithModifiedFile(
        changeId2, "file_in_latest_patch_set.txt", "Content of a file in latest patch set.");
    String currentPatchSetId = gApi.changes().id(changeId2).get().currentRevision;

    // Create an edit on top of that patch set and add a new file.
    gApi.changes()
        .id(changeId2)
        .edit()
        .modifyFile(
            "file_in_change_edit.txt",
            RawInputUtil.create("Content of the file added to the current change edit."));

    // Diff the edit against the patch set.
    Optional<EditInfo> edit =
        gApi.changes()
            .id(changeId2)
            .edit()
            .detail()
            .withOption(ChangeEditDetailOption.LIST_FILES)
            .withBase(currentPatchSetId)
            .get();

    assertThat(edit).value().files().keys().containsExactly(COMMIT_MSG, "file_in_change_edit.txt");
  }

  @Test
  public void editCanBeDiffedAgainstEarlierPatchSet() throws Exception {
    // Create two patch sets.
    addNewPatchSetWithModifiedFile(
        changeId2, "file_in_old_patch_set.txt", "Content of file in older patch set.");
    String previousPatchSetId = gApi.changes().id(changeId2).get().currentRevision;
    addNewPatchSetWithModifiedFile(
        changeId2, "file_in_latest_patch_set.txt", "Content of a file in latest patch set.");

    // Create an edit and add a new file.
    gApi.changes()
        .id(changeId2)
        .edit()
        .modifyFile(
            "file_in_change_edit.txt",
            RawInputUtil.create("Content of the file added to the current change edit."));

    // Diff the edit against the previous patch set.
    Optional<EditInfo> edit =
        gApi.changes()
            .id(changeId2)
            .edit()
            .detail()
            .withOption(ChangeEditDetailOption.LIST_FILES)
            .withBase(previousPatchSetId)
            .get();

    assertThat(edit)
        .value()
        .files()
        .keys()
        .containsExactly(COMMIT_MSG, "file_in_latest_patch_set.txt", "file_in_change_edit.txt");
  }

  @Test
  public void deleteExistingFile() throws Exception {
    createEmptyEditFor(changeId);
    gApi.changes().id(changeId).edit().deleteFile(FILE_NAME);
    assertThat(getFileContentOfEdit(changeId, FILE_NAME)).isAbsent();
  }

  @Test
  public void deleteExistingFileAfterUpdatingPreferredEmail() throws Exception {
    String emailOne = "email1@example.com";
    Account.Id testUser = accountOperations.newAccount().preferredEmail(emailOne).create();

    // Create change to edit
    Change.Id change =
        changeOperations
            .newChange()
            .project(project)
            .file(FILE_NAME)
            .content("content")
            .owner(testUser)
            .create();

    // Change preferred email for the user
    String emailTwo = "email2@example.com";
    accountOperations.account(testUser).forUpdate().preferredEmail(emailTwo).update();
    requestScopeOperations.setApiUser(testUser);

    // Delete file
    gApi.changes().id(change.get()).edit().deleteFile(FILE_NAME);

    EditInfo editInfo = gApi.changes().id(change.get()).edit().get().orElseThrow();
    assertThat(editInfo.commit.committer.email).isEqualTo(emailOne);
  }

  @Test
  public void renameExistingFile() throws Exception {
    createEmptyEditFor(changeId);
    gApi.changes().id(changeId).edit().renameFile(FILE_NAME, FILE_NAME3);
    ensureSameBytes(getFileContentOfEdit(changeId, FILE_NAME3), CONTENT_OLD);
    assertThat(getFileContentOfEdit(changeId, FILE_NAME)).isAbsent();
  }

  @Test
  public void renameExistingFileAfterUpdatingPreferredEmail() throws Exception {
    String emailOne = "email1@example.com";
    Account.Id testUser = accountOperations.newAccount().preferredEmail(emailOne).create();

    // Create change to edit
    Change.Id change =
        changeOperations
            .newChange()
            .project(project)
            .file(FILE_NAME)
            .content("content")
            .owner(testUser)
            .create();

    // Change preferred email for the user
    String emailTwo = "email2@example.com";
    accountOperations.account(testUser).forUpdate().preferredEmail(emailTwo).update();
    requestScopeOperations.setApiUser(testUser);

    // Rename file
    gApi.changes().id(change.get()).edit().renameFile(FILE_NAME, FILE_NAME3);

    EditInfo editInfo = gApi.changes().id(change.get()).edit().get().orElseThrow();
    assertThat(editInfo.commit.committer.email).isEqualTo(emailOne);
  }

  @Test
  public void renameExistingFileToInvalidPath() throws Exception {
    createEmptyEditFor(changeId);
    BadRequestException badRequest =
        assertThrows(
            BadRequestException.class,
            () -> gApi.changes().id(changeId).edit().renameFile(FILE_NAME, "invalid/path/"));
    assertThat(badRequest.getMessage()).isEqualTo("Invalid path: invalid/path/");
  }

  @Test
  public void createEditByDeletingExistingFileRest() throws Exception {
    adminRestSession.delete(urlEditFile(changeId, FILE_NAME)).assertNoContent();
    assertThat(getFileContentOfEdit(changeId, FILE_NAME)).isAbsent();
  }

  @Test
  public void deletingNonExistingEditRest() throws Exception {
    adminRestSession.delete(urlEdit(changeId)).assertNotFound();
  }

  @Test
  public void deleteExistingFileRest() throws Exception {
    createEmptyEditFor(changeId);
    adminRestSession.delete(urlEditFile(changeId, FILE_NAME)).assertNoContent();
    assertThat(getFileContentOfEdit(changeId, FILE_NAME)).isAbsent();
  }

  @Test
  public void restoreDeletedFileInPatchSet() throws Exception {
    createEmptyEditFor(changeId2);
    gApi.changes().id(changeId2).edit().restoreFile(FILE_NAME);
    ensureSameBytes(getFileContentOfEdit(changeId2, FILE_NAME), CONTENT_OLD);
  }

  @Test
  public void restoreFileAfterUpdatingPreferredEmail() throws Exception {
    String emailOne = "email1@example.com";
    Account.Id testUser = accountOperations.newAccount().preferredEmail(emailOne).create();

    // Create change to edit
    Change.Id change =
        changeOperations
            .newChange()
            .project(project)
            .file(FILE_NAME)
            .content("content")
            .owner(testUser)
            .create();

    // Change preferred email for the user
    String emailTwo = "email2@example.com";
    accountOperations.account(testUser).forUpdate().preferredEmail(emailTwo).update();
    requestScopeOperations.setApiUser(testUser);

    // Restore file to the state in the parent of change
    gApi.changes().id(change.get()).edit().restoreFile(FILE_NAME);

    EditInfo editInfo = gApi.changes().id(change.get()).edit().get().orElseThrow();
    assertThat(editInfo.commit.committer.email).isEqualTo(emailOne);
  }

  @Test
  public void revertChanges() throws Exception {
    createEmptyEditFor(changeId2);
    gApi.changes().id(changeId2).edit().restoreFile(FILE_NAME);
    ensureSameBytes(getFileContentOfEdit(changeId2, FILE_NAME), CONTENT_OLD);
    gApi.changes().id(changeId2).edit().modifyFile(FILE_NAME, RawInputUtil.create(CONTENT_NEW));
    ensureSameBytes(getFileContentOfEdit(changeId2, FILE_NAME), CONTENT_NEW);
    gApi.changes().id(changeId2).edit().restoreFile(FILE_NAME);
    ensureSameBytes(getFileContentOfEdit(changeId2, FILE_NAME), CONTENT_OLD);
  }

  @Test
  public void renameFileRest() throws Exception {
    createEmptyEditFor(changeId);
    Post.Input in = new Post.Input();
    in.oldPath = FILE_NAME;
    in.newPath = FILE_NAME3;
    adminRestSession.post(urlEdit(changeId), in).assertNoContent();
    ensureSameBytes(getFileContentOfEdit(changeId, FILE_NAME3), CONTENT_OLD);
    assertThat(getFileContentOfEdit(changeId, FILE_NAME)).isAbsent();
  }

  @Test
  public void restoreDeletedFileInPatchSetRest() throws Exception {
    Post.Input in = new Post.Input();
    in.restorePath = FILE_NAME;
    adminRestSession.post(urlEdit(changeId2), in).assertNoContent();
    ensureSameBytes(getFileContentOfEdit(changeId2, FILE_NAME), CONTENT_OLD);
  }

  @Test
  public void amendExistingFile() throws Exception {
    createEmptyEditFor(changeId);
    gApi.changes().id(changeId).edit().modifyFile(FILE_NAME, RawInputUtil.create(CONTENT_NEW));
    ensureSameBytes(getFileContentOfEdit(changeId, FILE_NAME), CONTENT_NEW);
    gApi.changes().id(changeId).edit().modifyFile(FILE_NAME, RawInputUtil.create(CONTENT_NEW2));
    ensureSameBytes(getFileContentOfEdit(changeId, FILE_NAME), CONTENT_NEW2);
  }

  @Test
  public void createAndChangeEditInOneRequestRest() throws Exception {
    FileContentInput in = new FileContentInput();
    in.content = RawInputUtil.create(CONTENT_NEW);
    adminRestSession.putRaw(urlEditFile(changeId, FILE_NAME), in.content).assertNoContent();
    ensureSameBytes(getFileContentOfEdit(changeId, FILE_NAME), CONTENT_NEW);
    in.content = RawInputUtil.create(CONTENT_NEW2);
    adminRestSession.putRaw(urlEditFile(changeId, FILE_NAME), in.content).assertNoContent();
    ensureSameBytes(getFileContentOfEdit(changeId, FILE_NAME), CONTENT_NEW2);
  }

  @Test
  public void changeEditRest() throws Exception {
    createEmptyEditFor(changeId);
    FileContentInput in = new FileContentInput();
    in.content = RawInputUtil.create(CONTENT_NEW);
    adminRestSession.putRaw(urlEditFile(changeId, FILE_NAME), in.content).assertNoContent();
    ensureSameBytes(getFileContentOfEdit(changeId, FILE_NAME), CONTENT_NEW);
  }

  @Test
  public void changeEditModifyFileModeRest() throws Exception {
    createEmptyEditFor(changeId);
    FileContentInput in = new FileContentInput();
    in.binary_content = CONTENT_BINARY_ENCODED_NEW;
    in.fileMode = FILE_MODE;
    adminRestSession.put(urlEditFile(changeId, FILE_NAME), in).assertNoContent();
    ensureSameBytes(getFileContentOfEdit(changeId, FILE_NAME), CONTENT_BINARY_DECODED_NEW);
  }

  @Test
  public void changeEditModifyFileSetEmptyContentModeRest() throws Exception {
    createEmptyEditFor(changeId);
    FileContentInput in = new FileContentInput();
    in.binary_content = CONTENT_BINARY_ENCODED_EMPTY;
    in.fileMode = FILE_MODE;
    adminRestSession.put(urlEditFile(changeId, FILE_NAME), in).assertNoContent();
    ensureSameBytes(getFileContentOfEdit(changeId, FILE_NAME), "".getBytes(UTF_8));
  }

  @Test
  public void createAndUploadBinaryInChangeEditOneRequestRest() throws Exception {
    FileContentInput in = new FileContentInput();
    in.binary_content = CONTENT_BINARY_ENCODED_NEW;
    adminRestSession.put(urlEditFile(changeId, FILE_NAME), in).assertNoContent();
    ensureSameBytes(getFileContentOfEdit(changeId, FILE_NAME), CONTENT_BINARY_DECODED_NEW);
    in.binary_content = CONTENT_BINARY_ENCODED_NEW2;
    adminRestSession.put(urlEditFile(changeId, FILE_NAME), in).assertNoContent();
    ensureSameBytes(getFileContentOfEdit(changeId, FILE_NAME), CONTENT_BINARY_DECODED_NEW2);
  }

  @Test
  public void invalidBase64UploadBinaryInChangeEditOneRequestRest() throws Exception {
    FileContentInput in = new FileContentInput();
    in.binary_content = CONTENT_BINARY_ENCODED_NEW3;
    adminRestSession.put(urlEditFile(changeId, FILE_NAME), in).assertBadRequest();
  }

  @Test
  public void changeEditNoContentProvidedRest() throws Exception {
    createEmptyEditFor(changeId);

    FileContentInput in = new FileContentInput();
    in.binary_content = null;
    adminRestSession.put(urlEditFile(changeId, FILE_NAME), in).assertBadRequest();
  }

  @Test
  public void emptyPutRequest() throws Exception {
    createEmptyEditFor(changeId);
    adminRestSession.put(urlEditFile(changeId, FILE_NAME)).assertNoContent();
    ensureSameBytes(getFileContentOfEdit(changeId, FILE_NAME), "".getBytes(UTF_8));
  }

  @Test
  public void createEmptyEditRest() throws Exception {
    adminRestSession.post(urlEdit(changeId)).assertNoContent();
    ensureSameBytes(getFileContentOfEdit(changeId, FILE_NAME), CONTENT_OLD);
  }

  @Test
  public void getFileContentRest() throws Exception {
    FileContentInput in = new FileContentInput();
    in.content = RawInputUtil.create(CONTENT_NEW);
    adminRestSession.putRaw(urlEditFile(changeId, FILE_NAME), in.content).assertNoContent();
    gApi.changes().id(changeId).edit().modifyFile(FILE_NAME, RawInputUtil.create(CONTENT_NEW2));
    RestResponse r = adminRestSession.getJsonAccept(urlEditFile(changeId, FILE_NAME));
    r.assertOK();
    assertThat(readContentFromJson(r)).isEqualTo(new String(CONTENT_NEW2, UTF_8));

    r = adminRestSession.getJsonAccept(urlEditFile(changeId, FILE_NAME, true));
    r.assertOK();
    assertThat(readContentFromJson(r)).isEqualTo(new String(CONTENT_OLD, UTF_8));
  }

  @Test
  public void getFileNotFoundRest() throws Exception {
    createEmptyEditFor(changeId);
    adminRestSession.delete(urlEditFile(changeId, FILE_NAME)).assertNoContent();
    adminRestSession.get(urlEditFile(changeId, FILE_NAME)).assertNoContent();
    assertThat(getFileContentOfEdit(changeId, FILE_NAME)).isAbsent();
  }

  @Test
  public void addNewFile() throws Exception {
    createEmptyEditFor(changeId);
    Optional<EditInfo> originalEdit =
        gApi.changes()
            .id(changeId)
            .edit()
            .detail()
            .withOption(ChangeEditDetailOption.LIST_FILES)
            .get();
    assertThat(originalEdit)
        .value()
        .files()
        .keys()
        .containsExactly(COMMIT_MSG, FILE_NAME, FILE_NAME2);

    gApi.changes().id(changeId).edit().modifyFile(FILE_NAME3, RawInputUtil.create(CONTENT_NEW));

    ensureSameBytes(getFileContentOfEdit(changeId, FILE_NAME3), CONTENT_NEW);
    Optional<EditInfo> adjustedEdit =
        gApi.changes()
            .id(changeId)
            .edit()
            .detail()
            .withOption(ChangeEditDetailOption.LIST_FILES)
            .get();
    assertThat(adjustedEdit)
        .value()
        .files()
        .keys()
        .containsExactly(COMMIT_MSG, FILE_NAME, FILE_NAME2, FILE_NAME3);
  }

  @Test
  public void addNewFileAndAmend() throws Exception {
    createEmptyEditFor(changeId);
    gApi.changes().id(changeId).edit().modifyFile(FILE_NAME3, RawInputUtil.create(CONTENT_NEW));
    ensureSameBytes(getFileContentOfEdit(changeId, FILE_NAME3), CONTENT_NEW);
    gApi.changes().id(changeId).edit().modifyFile(FILE_NAME3, RawInputUtil.create(CONTENT_NEW2));
    ensureSameBytes(getFileContentOfEdit(changeId, FILE_NAME3), CONTENT_NEW2);
  }

  @Test
  public void writeNoChanges() throws Exception {
    createEmptyEditFor(changeId);
    ResourceConflictException thrown =
        assertThrows(
            ResourceConflictException.class,
            () ->
                gApi.changes()
                    .id(changeId)
                    .edit()
                    .modifyFile(FILE_NAME, RawInputUtil.create(CONTENT_OLD)));
    assertThat(thrown).hasMessageThat().contains("no changes were made");
  }

  @Test
  public void editCommitMessageCopiesLabelScores() throws Exception {
    String cr = LabelId.CODE_REVIEW;
    try (ProjectConfigUpdate u = updateProject(project)) {
      LabelType codeReview = TestLabels.codeReview();
      u.getConfig().upsertLabelType(codeReview);
      u.getConfig()
          .updateLabelType(
              codeReview.getName(),
              lt -> lt.setCopyCondition("changekind:" + ChangeKind.NO_CODE_CHANGE.name()));
      u.save();
    }

    ReviewInput r = new ReviewInput();
    r.labels = ImmutableMap.of(cr, (short) 1);
    gApi.changes().id(changeId).current().review(r);

    createEmptyEditFor(changeId);
    String newSubj = "New commit message";
    String newMsg = newSubj + "\n\nChange-Id: " + changeId + "\n";
    gApi.changes().id(changeId).edit().modifyCommitMessage(newMsg);
    PublishChangeEditInput publishInput = new PublishChangeEditInput();
    publishInput.notify = NotifyHandling.NONE;
    gApi.changes().id(changeId).edit().publish(publishInput);

    ChangeInfo info = get(changeId, DETAILED_LABELS);
    assertThat(info.subject).isEqualTo(newSubj);
    List<ApprovalInfo> approvals = info.labels.get(cr).all;
    assertThat(approvals).hasSize(1);
    assertThat(approvals.get(0).value).isEqualTo(1);
  }

  @Test
  public void hasEditPredicate() throws Exception {
    createEmptyEditFor(changeId);
    assertThat(queryEdits()).hasSize(1);

    createEmptyEditFor(changeId2);
    gApi.changes().id(changeId2).edit().modifyFile(FILE_NAME, RawInputUtil.create(CONTENT_NEW));
    assertThat(queryEdits()).hasSize(2);

    gApi.changes().id(changeId).edit().modifyFile(FILE_NAME, RawInputUtil.create(CONTENT_NEW));
    gApi.changes().id(changeId).edit().delete();
    assertThat(queryEdits()).hasSize(1);

    PublishChangeEditInput publishInput = new PublishChangeEditInput();
    publishInput.notify = NotifyHandling.NONE;
    gApi.changes().id(changeId2).edit().publish(publishInput);
    assertThat(queryEdits()).isEmpty();

    requestScopeOperations.setApiUser(user.id());
    createEmptyEditFor(changeId);
    assertThat(queryEdits()).hasSize(1);

    requestScopeOperations.setApiUser(admin.id());
    assertThat(queryEdits()).isEmpty();
  }

  @Test
  public void files() throws Exception {
    createEmptyEditFor(changeId);
    gApi.changes().id(changeId).edit().modifyFile(FILE_NAME, RawInputUtil.create(CONTENT_NEW));
    Optional<EditInfo> edit = getEdit(changeId);
    assertThat(edit).isPresent();
    String editCommitId = edit.get().commit.commit;

    RestResponse r = adminRestSession.getJsonAccept(urlRevisionFiles(changeId, editCommitId));
    Map<String, FileInfo> files = readContentFromJson(r, new TypeToken<Map<String, FileInfo>>() {});
    assertThat(files).containsKey(FILE_NAME);

    r = adminRestSession.getJsonAccept(urlRevisionFiles(changeId));
    files = readContentFromJson(r, new TypeToken<Map<String, FileInfo>>() {});
    assertThat(files).containsKey(FILE_NAME);
  }

  @Test
  public void diff() throws Exception {
    createEmptyEditFor(changeId);
    gApi.changes().id(changeId).edit().modifyFile(FILE_NAME, RawInputUtil.create(CONTENT_NEW));
    Optional<EditInfo> edit = getEdit(changeId);
    assertThat(edit).isPresent();
    String editCommitId = edit.get().commit.commit;

    RestResponse r = adminRestSession.getJsonAccept(urlDiff(changeId, editCommitId, FILE_NAME));
    DiffInfo diff = readContentFromJson(r, DiffInfo.class);
    assertThat(diff.diffHeader.get(0)).contains(FILE_NAME);

    r = adminRestSession.getJsonAccept(urlDiff(changeId, FILE_NAME));
    diff = readContentFromJson(r, DiffInfo.class);
    assertThat(diff.diffHeader.get(0)).contains(FILE_NAME);
  }

  @Test
  public void createEditWithoutPushPatchSetPermission() throws Exception {
    // Create new project with clean permissions
    Project.NameKey p = projectOperations.newProject().create();
    // Clone repository as user
    TestRepository<InMemoryRepository> userTestRepo = cloneProject(p, user);

    // Block default permission
    projectOperations
        .project(p)
        .forUpdate()
        .add(block(Permission.ADD_PATCH_SET).ref("refs/for/*").group(REGISTERED_USERS))
        .update();

    // Create change as user
    PushOneCommit push = pushFactory.create(user.newIdent(), userTestRepo);
    PushOneCommit.Result r1 = push.to("refs/for/master");
    r1.assertOkStatus();

    // Try to create edit as admin
    assertThrows(AuthException.class, () -> createEmptyEditFor(r1.getChangeId()));
  }

  @Test
  public void editCannotBeCreatedOnMergedChange() throws Exception {
    ChangeInfo change = gApi.changes().id(changeId).get();
    gApi.changes().id(changeId).current().review(ReviewInput.approve());
    gApi.changes().id(changeId).current().submit();

    ResourceConflictException thrown =
        assertThrows(ResourceConflictException.class, () -> createArbitraryEditFor(changeId));
    assertThat(thrown)
        .hasMessageThat()
        .contains(String.format("change %s is merged", change._number));
  }

  @Test
  public void editCannotBeCreatedOnAbandonedChange() throws Exception {
    ChangeInfo change = gApi.changes().id(changeId).get();
    gApi.changes().id(changeId).abandon();

    ResourceConflictException thrown =
        assertThrows(ResourceConflictException.class, () -> createArbitraryEditFor(changeId));
    assertThat(thrown)
        .hasMessageThat()
        .contains(String.format("change %s is abandoned", change._number));
  }

  @Test
  public void sha1sOfTwoChangesWithSameContentAfterEditDiffer() throws Exception {
    ChangeInput changeInput = new ChangeInput();
    changeInput.project = project.get();
    changeInput.branch = "master";
    changeInput.subject = "Empty change";
    changeInput.status = ChangeStatus.NEW;

    ChangeInfo info1 = gApi.changes().create(changeInput).get();
    gApi.changes().id(info1._number).edit().modifyFile(FILE_NAME, RawInputUtil.create(CONTENT_NEW));
    gApi.changes().id(info1._number).edit().publish(new PublishChangeEditInput());
    info1 = gApi.changes().id(info1._number).get();

    ChangeInfo info2 = gApi.changes().create(changeInput).get();
    gApi.changes().id(info2._number).edit().modifyFile(FILE_NAME, RawInputUtil.create(CONTENT_NEW));
    gApi.changes().id(info2._number).edit().publish(new PublishChangeEditInput());
    info2 = gApi.changes().id(info2._number).get();

    assertThat(info1.currentRevision).isNotEqualTo(info2.currentRevision);
  }

  @Test
  public void canCombineEdits() throws Exception {
    createEmptyEditFor(changeId);

    // update author
    gApi.changes()
        .id(changeId)
        .edit()
        .modifyIdentity("Test Author", "test.author@example.com", ChangeEditIdentityType.AUTHOR);

    // update message
    String msg = String.format("New commit message\n\nChange-Id: %s\n", changeId);
    gApi.changes().id(changeId).edit().modifyCommitMessage(msg);

    // add new file
    String newFile = "foobar";
    gApi.changes().id(changeId).edit().modifyFile(newFile, RawInputUtil.create(CONTENT_NEW));

    // update committer
    gApi.changes()
        .id(changeId)
        .edit()
        .modifyIdentity(
            "Test Committer", "test.committer@example.com", ChangeEditIdentityType.COMMITTER);

    // delete file
    gApi.changes().id(changeId).edit().deleteFile(FILE_NAME);

    // rename file
    gApi.changes().id(changeId).edit().renameFile(FILE_NAME2, FILE_NAME3);

    // publish edit
    PublishChangeEditInput publishInput = new PublishChangeEditInput();
    publishInput.notify = NotifyHandling.NONE;
    gApi.changes().id(changeId).edit().publish(publishInput);
    assertThat(getEdit(changeId)).isAbsent();

    ChangeInfo info =
        get(
            changeId,
            ListChangesOption.CURRENT_COMMIT,
            ListChangesOption.CURRENT_REVISION,
            ListChangesOption.ALL_FILES);
    RevisionInfo currentRevision = info.revisions.get(info.currentRevision);
    CommitInfo currentCommit = currentRevision.commit;
    assertThat(currentCommit.committer.name).isEqualTo("Test Committer");
    assertThat(currentCommit.committer.email).isEqualTo("test.committer@example.com");
    assertThat(currentCommit.author.name).isEqualTo("Test Author");
    assertThat(currentCommit.author.email).isEqualTo("test.author@example.com");
    assertThat(currentCommit.message).isEqualTo(msg);
    assertThat(currentRevision.files.keySet()).containsExactly(newFile, FILE_NAME3);
  }

  private void createArbitraryEditFor(String changeId) throws Exception {
    createEmptyEditFor(changeId);
    arbitrarilyModifyEditOf(changeId);
  }

  private void createEmptyEditFor(String changeId) throws Exception {
    gApi.changes().id(changeId).edit().create();
  }

  private void arbitrarilyModifyEditOf(String changeId) throws Exception {
    gApi.changes().id(changeId).edit().modifyFile(FILE_NAME, RawInputUtil.create(CONTENT_NEW));
  }

  private Optional<BinaryResult> getFileContentOfEdit(String changeId, String filePath)
      throws Exception {
    return gApi.changes().id(changeId).edit().getFile(filePath);
  }

  private List<ChangeInfo> queryEdits() throws Exception {
    return query("project:{" + project.get() + "} has:edit");
  }

  private String newChange(PersonIdent ident) throws Exception {
    PushOneCommit push =
        pushFactory.create(
            ident, testRepo, PushOneCommit.SUBJECT, FILE_NAME, new String(CONTENT_OLD, UTF_8));
    return push.to("refs/for/master").getChangeId();
  }

  private void addNewPatchSet(String changeId) throws Exception {
    addNewPatchSetWithModifiedFile(changeId, FILE_NAME2, new String(CONTENT_NEW2, UTF_8));
  }

  private void addNewPatchSetWithModifiedFile(String changeId, String filePath, String fileContent)
      throws Exception {
    PushOneCommit push =
        pushFactory.create(
            admin.newIdent(), testRepo, PushOneCommit.SUBJECT, filePath, fileContent, changeId);
    push.to("refs/for/master");
  }

  private String newChange2(PersonIdent ident) throws Exception {
    PushOneCommit push =
        pushFactory.create(
            ident, testRepo, PushOneCommit.SUBJECT, FILE_NAME, new String(CONTENT_OLD, UTF_8));
    return push.rm("refs/for/master").getChangeId();
  }

  private PatchSet getCurrentPatchSet(String changeId) throws Exception {
    return getOnlyElement(queryProvider.get().byKeyPrefix(changeId)).currentPatchSet();
  }

  private void ensureSameBytes(Optional<BinaryResult> fileContent, byte[] expectedFileBytes)
      throws IOException {
    assertThat(fileContent).value().bytes().isEqualTo(expectedFileBytes);
  }

  private String urlEdit(String changeId) {
    return "/changes/" + changeId + "/edit";
  }

  private String urlEditMessage(String changeId, boolean base) {
    return "/changes/" + changeId + "/edit:message" + (base ? "?base" : "");
  }

  private String urlEditIdentity(String changeId) {
    return "/changes/" + changeId + "/edit:identity";
  }

  private String urlEditFile(String changeId, String fileName) {
    return urlEditFile(changeId, fileName, false);
  }

  private String urlEditFile(String changeId, String fileName, boolean base) {
    return urlEdit(changeId) + "/" + fileName + (base ? "?base" : "");
  }

  private String urlRevisionFiles(String changeId, String revisionId) {
    return "/changes/" + changeId + "/revisions/" + revisionId + "/files";
  }

  private String urlRevisionFiles(String changeId) {
    return "/changes/" + changeId + "/revisions/0/files";
  }

  private String urlPublish(String changeId) {
    return "/changes/" + changeId + "/edit:publish";
  }

  private String urlRebase(String changeId) {
    return "/changes/" + changeId + "/edit:rebase";
  }

  private String urlDiff(String changeId, String fileName) {
    return "/changes/" + changeId + "/revisions/0/files/" + fileName + "/diff?intraline";
  }

  private String urlDiff(String changeId, String revisionId, String fileName) {
    return "/changes/"
        + changeId
        + "/revisions/"
        + revisionId
        + "/files/"
        + fileName
        + "/diff?intraline";
  }

  private <T> T readContentFromJson(RestResponse r, Class<T> clazz) throws Exception {
    r.assertOK();
    try (JsonReader jsonReader = new JsonReader(r.getReader())) {
      jsonReader.setLenient(true);
      return newGson().fromJson(jsonReader, clazz);
    }
  }

  private <T> T readContentFromJson(RestResponse r, TypeToken<T> typeToken) throws Exception {
    r.assertOK();
    try (JsonReader jsonReader = new JsonReader(r.getReader())) {
      jsonReader.setLenient(true);
      return newGson().fromJson(jsonReader, typeToken.getType());
    }
  }

  private String readContentFromJson(RestResponse r) throws Exception {
    return readContentFromJson(r, String.class);
  }

  private void assertChangeMessages(String changeId, List<String> expectedMessages)
      throws Exception {
    ChangeInfo ci = get(changeId, MESSAGES);
    assertThat(ci.messages).isNotNull();
    assertThat(ci.messages).hasSize(expectedMessages.size());
    List<String> actualMessages =
        ci.messages.stream().map(message -> message.message).collect(toList());
    assertThat(actualMessages).containsExactlyElementsIn(expectedMessages).inOrder();
  }
}
