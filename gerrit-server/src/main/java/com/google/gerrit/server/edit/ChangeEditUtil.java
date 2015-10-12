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

package com.google.gerrit.server.edit;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.base.Optional;
import com.google.gerrit.common.TimeUtil;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Change.Status;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.change.ChangeKind;
import com.google.gerrit.server.change.ChangeKindCache;
import com.google.gerrit.server.change.PatchSetInserter;
import com.google.gerrit.server.git.BatchUpdate;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.UpdateException;
import com.google.gerrit.server.index.ChangeIndexer;
import com.google.gerrit.server.project.ChangeControl;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;

import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;

/**
 * Utility functions to manipulate change edits.
 * <p>
 * This class contains methods to retrieve, publish and delete edits.
 * For changing edits see {@link ChangeEditModifier}.
 */
@Singleton
public class ChangeEditUtil {
  private final GitRepositoryManager gitManager;
  private final PatchSetInserter.Factory patchSetInserterFactory;
  private final ChangeControl.GenericFactory changeControlFactory;
  private final ChangeIndexer indexer;
  private final ProjectCache projectCache;
  private final Provider<ReviewDb> db;
  private final Provider<CurrentUser> user;
  private final ChangeKindCache changeKindCache;
  private final BatchUpdate.Factory updateFactory;

  @Inject
  ChangeEditUtil(GitRepositoryManager gitManager,
      PatchSetInserter.Factory patchSetInserterFactory,
      ChangeControl.GenericFactory changeControlFactory,
      ChangeIndexer indexer,
      ProjectCache projectCache,
      Provider<ReviewDb> db,
      Provider<CurrentUser> user,
      ChangeKindCache changeKindCache,
      BatchUpdate.Factory updateFactory) {
    this.gitManager = gitManager;
    this.patchSetInserterFactory = patchSetInserterFactory;
    this.changeControlFactory = changeControlFactory;
    this.indexer = indexer;
    this.projectCache = projectCache;
    this.db = db;
    this.user = user;
    this.changeKindCache = changeKindCache;
    this.updateFactory = updateFactory;
  }

  /**
   * Retrieve edits for a change and user. Max. one change edit can
   * exist per user and change.
   *
   * @param change
   * @return edit for this change for this user, if present.
   * @throws AuthException
   * @throws IOException
   */
  public Optional<ChangeEdit> byChange(Change change)
      throws AuthException, IOException {
    CurrentUser currentUser = user.get();
    if (!currentUser.isIdentifiedUser()) {
      throw new AuthException("Authentication required");
    }
    return byChange(change, (IdentifiedUser)currentUser);
  }

  /**
   * Retrieve edits for a change and user. Max. one change edit can
   * exist per user and change.
   *
   * @param change
   * @param user to retrieve change edits for
   * @return edit for this change for this user, if present.
   * @throws IOException
   */
  public Optional<ChangeEdit> byChange(Change change, IdentifiedUser user)
      throws IOException {
    try (Repository repo = gitManager.openRepository(change.getProject())) {
      int n = change.currentPatchSetId().get();
      String[] refNames = new String[n];
      for (int i = n; i > 0; i--) {
        refNames[i-1] = RefNames.refsEdit(user.getAccountId(), change.getId(),
            new PatchSet.Id(change.getId(), i));
      }
      Ref ref = repo.getRefDatabase().firstExactRef(refNames);
      if (ref == null) {
        return Optional.absent();
      }
      try (RevWalk rw = new RevWalk(repo)) {
        RevCommit commit = rw.parseCommit(ref.getObjectId());
        PatchSet basePs = getBasePatchSet(change, ref);
        return Optional.of(new ChangeEdit(user, change, ref, commit, basePs));
      }
    }
  }

  /**
   * Promote change edit to patch set, by squashing the edit into
   * its parent.
   *
   * @param edit change edit to publish
   * @throws NoSuchChangeException
   * @throws IOException
   * @throws OrmException
   * @throws UpdateException
   * @throws RestApiException
   */
  public void publish(ChangeEdit edit) throws NoSuchChangeException,
      IOException, OrmException, RestApiException, UpdateException {
    Change change = edit.getChange();
    try (Repository repo = gitManager.openRepository(change.getProject());
        RevWalk rw = new RevWalk(repo);
        ObjectInserter inserter = repo.newObjectInserter()) {
      PatchSet basePatchSet = edit.getBasePatchSet();
      if (!basePatchSet.getId().equals(change.currentPatchSetId())) {
        throw new ResourceConflictException(
            "only edit for current patch set can be published");
      }

      Change updatedChange =
          insertPatchSet(edit, change, repo, rw, basePatchSet,
              squashEdit(rw, inserter, edit.getEditCommit(), basePatchSet));
      // TODO(davido): This should happen in the same BatchRefUpdate.
      deleteRef(repo, edit);
      indexer.index(db.get(), updatedChange);
    }
  }

  /**
   * Delete change edit.
   *
   * @param edit change edit to delete
   * @throws IOException
   */
  public void delete(ChangeEdit edit)
      throws IOException {
    Change change = edit.getChange();
    try (Repository repo = gitManager.openRepository(change.getProject())) {
      deleteRef(repo, edit);
    }
    indexer.index(db.get(), change);
  }

  private PatchSet getBasePatchSet(Change change, Ref ref)
      throws IOException {
    try {
      int pos = ref.getName().lastIndexOf("/");
      checkArgument(pos > 0, "invalid edit ref: %s", ref.getName());
      String psId = ref.getName().substring(pos + 1);
      return db.get().patchSets().get(new PatchSet.Id(
          change.getId(), Integer.parseInt(psId)));
    } catch (OrmException | NumberFormatException e) {
      throw new IOException(e);
    }
  }

  private RevCommit squashEdit(RevWalk rw, ObjectInserter inserter,
      RevCommit edit, PatchSet basePatchSet)
      throws IOException, ResourceConflictException {
    RevCommit parent = rw.parseCommit(ObjectId.fromString(
        basePatchSet.getRevision().get()));
    if (parent.getTree().equals(edit.getTree())
        && edit.getFullMessage().equals(parent.getFullMessage())) {
      throw new ResourceConflictException("identical tree and message");
    }
    return writeSquashedCommit(rw, inserter, parent, edit);
  }

  private Change insertPatchSet(ChangeEdit edit, Change change,
      Repository repo, RevWalk rw, PatchSet basePatchSet, RevCommit squashed)
      throws NoSuchChangeException, RestApiException, UpdateException,
      IOException {
    ChangeControl ctl = changeControlFactory.controlFor(change, edit.getUser());
    PatchSetInserter inserter =
        patchSetInserterFactory.create(repo, rw, ctl, squashed);

    StringBuilder message = new StringBuilder("Patch set ")
      .append(inserter.getPatchSetId().get())
      .append(": ");

    ProjectState project = projectCache.get(change.getDest().getParentKey());
    // Previously checked that the base patch set is the current patch set.
    ObjectId prior = ObjectId.fromString(basePatchSet.getRevision().get());
    ChangeKind kind = changeKindCache.getChangeKind(project, repo, prior, squashed);
    if (kind == ChangeKind.NO_CODE_CHANGE) {
      message.append("Commit message was updated.");
    } else {
      message.append("Published edit on patch set ")
        .append(basePatchSet.getPatchSetId())
        .append(".");
    }

    try (BatchUpdate bu = updateFactory.create(
        db.get(), change.getProject(), ctl.getCurrentUser(),
        TimeUtil.nowTs())) {
      bu.addOp(change.getId(), inserter
        .setDraft(change.getStatus() == Status.DRAFT ||
            basePatchSet.isDraft())
        .setMessage(message.toString()));
      bu.execute();
    }

    return inserter.getChange();
  }

  private static void deleteRef(Repository repo, ChangeEdit edit)
      throws IOException {
    String refName = edit.getRefName();
    RefUpdate ru = repo.updateRef(refName, true);
    ru.setExpectedOldObjectId(edit.getRef().getObjectId());
    ru.setForceUpdate(true);
    RefUpdate.Result result = ru.delete();
    switch (result) {
      case FORCED:
      case NEW:
      case NO_CHANGE:
        break;
      default:
        throw new IOException(String.format("Failed to delete ref %s: %s",
            refName, result));
    }
  }

  private static RevCommit writeSquashedCommit(RevWalk rw,
      ObjectInserter inserter, RevCommit parent, RevCommit edit)
      throws IOException {
    CommitBuilder mergeCommit = new CommitBuilder();
    for (int i = 0; i < parent.getParentCount(); i++) {
      mergeCommit.addParentId(parent.getParent(i));
    }
    mergeCommit.setAuthor(parent.getAuthorIdent());
    mergeCommit.setMessage(edit.getFullMessage());
    mergeCommit.setCommitter(edit.getCommitterIdent());
    mergeCommit.setTreeId(edit.getTree());

    return rw.parseCommit(commit(inserter, mergeCommit));
  }

  private static ObjectId commit(ObjectInserter inserter,
      CommitBuilder mergeCommit) throws IOException {
    ObjectId id = inserter.insert(mergeCommit);
    inserter.flush();
    return id;
  }
}
