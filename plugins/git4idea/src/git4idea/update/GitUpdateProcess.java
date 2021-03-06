// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package git4idea.update;

import com.google.common.annotations.VisibleForTesting;
import com.intellij.dvcs.DvcsUtil;
import com.intellij.dvcs.branch.DvcsSyncSettings;
import com.intellij.openapi.application.AccessToken;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.impl.LocalChangesUnderRoots;
import com.intellij.openapi.vcs.update.UpdatedFiles;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ObjectUtils;
import com.intellij.util.containers.ContainerUtil;
import git4idea.GitLocalBranch;
import git4idea.GitUtil;
import git4idea.branch.GitBranchPair;
import git4idea.branch.GitBranchUtil;
import git4idea.commands.Git;
import git4idea.config.GitVcsSettings;
import git4idea.config.GitVersionSpecialty;
import git4idea.config.UpdateMethod;
import git4idea.merge.GitConflictResolver;
import git4idea.merge.GitMergeCommittingConflictResolver;
import git4idea.merge.GitMerger;
import git4idea.rebase.GitRebaser;
import git4idea.repo.GitBranchTrackInfo;
import git4idea.repo.GitRepository;
import git4idea.util.GitPreservingProcess;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static com.intellij.dvcs.DvcsUtil.getShortRepositoryName;
import static git4idea.GitUtil.getRootsFromRepositories;
import static git4idea.GitUtil.mention;
import static git4idea.util.GitUIUtil.*;

/**
 * Handles update process (pull via merge or rebase) for several roots.
 *
 * The class is not thread-safe and is stateful. It is intended to be used only once.
 */
public class GitUpdateProcess {
  private static final Logger LOG = Logger.getInstance(GitUpdateProcess.class);

  @NotNull private final Project myProject;
  @NotNull private final Git myGit;
  @NotNull private final ProjectLevelVcsManager myVcsManager;
  @NotNull private final ChangeListManager myChangeListManager;

  @NotNull private final List<GitRepository> myRepositories;
  private final boolean myCheckRebaseOverMergeProblem;
  private final boolean myCheckForTrackedBranchExistence;
  private final UpdatedFiles myUpdatedFiles;
  @NotNull private final ProgressIndicator myProgressIndicator;
  @NotNull private final GitMerger myMerger;

  @NotNull private final Map<GitRepository, String> mySkippedRoots = new LinkedHashMap<>();

  public GitUpdateProcess(@NotNull Project project,
                          @Nullable ProgressIndicator progressIndicator,
                          @NotNull Collection<GitRepository> repositories,
                          @NotNull UpdatedFiles updatedFiles,
                          boolean checkRebaseOverMergeProblem,
                          boolean checkForTrackedBranchExistence) {
    myProject = project;
    myCheckRebaseOverMergeProblem = checkRebaseOverMergeProblem;
    myCheckForTrackedBranchExistence = checkForTrackedBranchExistence;
    myGit = Git.getInstance();
    myChangeListManager = ChangeListManager.getInstance(project);
    myVcsManager = ProjectLevelVcsManager.getInstance(project);
    myUpdatedFiles = updatedFiles;

    myRepositories = GitUtil.getRepositoryManager(project).sortByDependency(repositories);
    myProgressIndicator = progressIndicator == null ? new EmptyProgressIndicator() : progressIndicator;
    myMerger = new GitMerger(myProject);
  }

  /**
   * Checks if update is possible, saves local changes and updates all roots.
   * In case of error shows notification and returns false. If update completes without errors, returns true.
   *
   * Perform update on all roots.
   * 0. Blocks reloading project on external change, saving/syncing on frame deactivation.
   * 1. Checks if update is possible (rebase/merge in progress, no tracked branches...) and provides merge dialog to solve problems.
   * 2. Finds updaters to use (merge or rebase).
   * 3. Preserves local changes if needed (not needed for merge sometimes).
   * 4. Updates via 'git pull' or equivalent.
   * 5. Restores local changes if update completed or failed with error. If update is incomplete, i.e. some unmerged files remain,
   * local changes are not restored.
   *
   */
  @NotNull
  public GitUpdateResult update(final UpdateMethod updateMethod) {
    LOG.info("update started|" + updateMethod);
    String oldText = myProgressIndicator.getText();
    myProgressIndicator.setText("Updating...");

    for (GitRepository repository : myRepositories) {
      repository.update();
    }

    // check if update is possible
    if (checkRebaseInProgress() || isMergeInProgress() || areUnmergedFiles()) {
      return GitUpdateResult.NOT_READY;
    }
    Map<GitRepository, GitBranchPair> trackedBranches = checkTrackedBranchesConfiguration();
    if (ContainerUtil.isEmpty(trackedBranches)) {
      return GitUpdateResult.NOT_READY;
    }

    if (!fetchAndNotify(trackedBranches.keySet())) {
      return GitUpdateResult.NOT_READY;
    }

    GitUpdateResult result;
    try (AccessToken ignore = DvcsUtil.workingTreeChangeStarted(myProject, "VCS Update")) {
      result = updateImpl(updateMethod);
    }
    myProgressIndicator.setText(oldText);
    return result;
  }

  @NotNull
  private GitUpdateResult updateImpl(@NotNull UpdateMethod updateMethod) {
    Map<GitRepository, GitBranchPair> trackedBranches = checkTrackedBranchesConfiguration();
    if (trackedBranches == null) {
      return GitUpdateResult.NOT_READY;
    }

    Map<GitRepository, GitUpdater> updaters;
    try {
      updaters = defineUpdaters(updateMethod, trackedBranches);
    }
    catch (VcsException e) {
      LOG.info(e);
      notifyError(myProject, "Git update failed", e.getMessage(), true, e);
      return GitUpdateResult.ERROR;
    }

    if (updaters.isEmpty()) {
      return GitUpdateResult.NOTHING_TO_UPDATE;
    }

    updaters = tryFastForwardMergeForRebaseUpdaters(updaters);

    if (updaters.isEmpty()) {
      // everything was updated via the fast-forward merge
      return GitUpdateResult.SUCCESS;
    }

    if (myCheckRebaseOverMergeProblem) {
      Collection<GitRepository> problematicRoots = findRootsRebasingOverMerge(updaters);
      if (!problematicRoots.isEmpty()) {
        GitRebaseOverMergeProblem.Decision decision = GitRebaseOverMergeProblem.showDialog();
        if (decision == GitRebaseOverMergeProblem.Decision.MERGE_INSTEAD) {
          for (GitRepository repo : problematicRoots) {
            GitBranchPair branchAndTracked = trackedBranches.get(repo);
            if (branchAndTracked == null) {
              LOG.error("No tracked branch information for root " + repo.getRoot());
              continue;
            }
            updaters.put(repo, new GitMergeUpdater(myProject, myGit, repo, branchAndTracked, myProgressIndicator, myUpdatedFiles));
          }
        }
        else if (decision == GitRebaseOverMergeProblem.Decision.CANCEL_OPERATION) {
          return GitUpdateResult.CANCEL;
        }
      }
    }

    // save local changes if needed (update via merge may perform without saving).
    final Collection<VirtualFile> myRootsToSave = ContainerUtil.newArrayList();
    LOG.info("updateImpl: identifying if save is needed...");
    for (Map.Entry<GitRepository, GitUpdater> entry : updaters.entrySet()) {
      GitRepository repo = entry.getKey();
      GitUpdater updater = entry.getValue();
      if (updater.isSaveNeeded()) {
        myRootsToSave.add(repo.getRoot());
        LOG.info("update| root " + repo + " needs save");
      }
    }

    LOG.info("updateImpl: saving local changes...");
    final Ref<Boolean> incomplete = Ref.create(false);
    final Ref<GitUpdateResult> compoundResult = Ref.create();
    final Map<GitRepository, GitUpdater> finalUpdaters = updaters;
    new GitPreservingProcess(myProject, myGit, myRootsToSave, "Update", "Remote",
                             GitVcsSettings.getInstance(myProject).updateChangesPolicy(), myProgressIndicator, () -> {
                               LOG.info("updateImpl: updating...");
                               GitRepository currentlyUpdatedRoot = null;
                               try {
                                 for (GitRepository repo : myRepositories) {
                                   GitUpdater updater = finalUpdaters.get(repo);
                                   if (updater == null) continue;
                                   currentlyUpdatedRoot = repo;
                                   GitUpdateResult res = updater.update();
                                   LOG.info("updating root " + currentlyUpdatedRoot + " finished: " + res);
                                   if (res == GitUpdateResult.INCOMPLETE) {
                                     incomplete.set(true);
                                   }
                                   compoundResult.set(joinResults(compoundResult.get(), res));
                                 }
                               }
                               catch (VcsException e) {
                                 String rootName = (currentlyUpdatedRoot == null) ? "" : getShortRepositoryName(currentlyUpdatedRoot);
                                 LOG.info("Error updating changes for root " + currentlyUpdatedRoot, e);
                                 notifyImportantError(myProject, "Error updating " + rootName,
                                                      "Updating " + rootName + " failed with an error: " + e.getLocalizedMessage());
                               }
                             }).execute(() -> {
      // Note: compoundResult normally should not be null, because the updaters map was checked for non-emptiness.
      // But if updater.update() fails with exception for the first root, then the value would not be assigned.
      // In this case we don't restore local changes either, because update failed.
      return !incomplete.get() && !compoundResult.isNull() && compoundResult.get().isSuccess();
    });
    // GitPreservingProcess#save may fail due index.lock presence
    return ObjectUtils.notNull(compoundResult.get(), GitUpdateResult.ERROR);
  }

  @NotNull
  private Collection<GitRepository> findRootsRebasingOverMerge(@NotNull Map<GitRepository, GitUpdater> updaters) {
    return ContainerUtil.mapNotNull(updaters.keySet(), repo -> {
      GitUpdater updater = updaters.get(repo);
      if (updater instanceof GitRebaseUpdater) {
        String currentRef = updater.getSourceAndTarget().getBranch().getFullName();
        String baseRef = ObjectUtils.assertNotNull(updater.getSourceAndTarget().getDest()).getFullName();
        return GitRebaseOverMergeProblem.hasProblem(myProject, repo.getRoot(), baseRef, currentRef) ? repo : null;
      }
      return null;
    });
  }

  @NotNull
  private Map<GitRepository, GitUpdater> tryFastForwardMergeForRebaseUpdaters(@NotNull Map<GitRepository, GitUpdater> updaters) {
    Map<GitRepository, GitUpdater> modifiedUpdaters = new HashMap<>();
    Map<VirtualFile, Collection<Change>> changesUnderRoots =
      new LocalChangesUnderRoots(myChangeListManager, myVcsManager).getChangesUnderRoots(getRootsFromRepositories(updaters.keySet()));
    for (GitRepository repository : myRepositories) {
      GitUpdater updater = updaters.get(repository);
      if (updater == null) continue;
      Collection<Change> changes = changesUnderRoots.get(repository.getRoot());
      LOG.debug("Changes under root '" + getShortRepositoryName(repository) + "': " + changes);
      if (updater instanceof GitRebaseUpdater && changes != null && !changes.isEmpty()) {
        // check only if there are local changes, otherwise stash won't happen anyway and there would be no optimization
        GitRebaseUpdater rebaseUpdater = (GitRebaseUpdater) updater;
        if (rebaseUpdater.fastForwardMerge()) {
          continue;
        }
      }
      modifiedUpdaters.put(repository, updater);
    }
    return modifiedUpdaters;
  }

  @NotNull
  private Map<GitRepository, GitUpdater> defineUpdaters(@NotNull UpdateMethod updateMethod,
                                                        @NotNull Map<GitRepository, GitBranchPair> trackedBranches) throws VcsException {
    final Map<GitRepository, GitUpdater> updaters = new HashMap<>();
    LOG.info("updateImpl: defining updaters...");
    for (GitRepository repository : myRepositories) {
      GitBranchPair branchAndTracked = trackedBranches.get(repository);
      if (branchAndTracked == null) continue;
      GitUpdater updater = GitUpdater.getUpdater(myProject, myGit, branchAndTracked, repository, myProgressIndicator, myUpdatedFiles,
                                                 updateMethod);
      if (updater.isUpdateNeeded()) {
        updaters.put(repository, updater);
      }
      LOG.info("update| root=" + repository.getRoot() + " ,updater=" + updater);
    }
    return updaters;
  }

  @NotNull
  Map<GitRepository, String> getSkippedRoots() {
    return mySkippedRoots;
  }

  @NotNull
  private static GitUpdateResult joinResults(@Nullable GitUpdateResult compoundResult, GitUpdateResult result) {
    if (compoundResult == null) {
      return result;
    }
    return compoundResult.join(result);
  }

  // fetch all roots. If an error happens, return false and notify about errors.
  private boolean fetchAndNotify(@NotNull Collection<GitRepository> repositories) {
    return new GitFetcher(myProject, myProgressIndicator, false).fetchRootsAndNotify(repositories, "Update failed", false);
  }

  /**
   * For each root check that the repository is on branch, and this branch is tracking a remote branch, and the remote branch exists.
   * If it is not true for at least one of roots, notify and return null.
   * If branch configuration is OK for all roots, return the collected tracking branch information.
   */
  @Nullable
  private Map<GitRepository, GitBranchPair> checkTrackedBranchesConfiguration() {
    LOG.info("checking tracked branch configuration...");

    Map<GitRepository, GitLocalBranch> currentBranches = ContainerUtil.newLinkedHashMap();
    List<GitRepository> detachedHeads = ContainerUtil.newArrayList();
    for (GitRepository repository : myRepositories) {
      GitLocalBranch branch = repository.getCurrentBranch();
      if (branch != null) {
        currentBranches.put(repository, branch);
      }
      else {
        detachedHeads.add(repository);
        LOG.info("checkTrackedBranchesConfigured: current branch is null in " + repository);
      }
    }

    if (currentBranches.isEmpty() || (isSyncControl() && (currentBranches.size() < myRepositories.size()))) {
      notifyDetachedHeadError(detachedHeads.get(0));
      return null;
    }
    else {
      for (GitRepository repo : detachedHeads) {
        mySkippedRoots.put(repo, "detached HEAD");
      }
    }

    Map<GitRepository, GitBranchPair> trackedBranches = ContainerUtil.newLinkedHashMap();
    List<GitRepository> noTrackedBranch = ContainerUtil.newArrayList();
    for (GitRepository repository: currentBranches.keySet()) {
      GitLocalBranch branch = currentBranches.get(repository);
      GitBranchTrackInfo trackInfo = GitBranchUtil.getTrackInfoForBranch(repository, branch);
      if (trackInfo != null) {
        trackedBranches.put(repository, new GitBranchPair(branch, trackInfo.getRemoteBranch()));
      }
      else {
        noTrackedBranch.add(repository);
        LOG.info(String.format("checkTrackedBranchesConfiguration: no track info for current branch %s in %s", branch, repository));
      }
    }

    if (myCheckForTrackedBranchExistence &&
        (trackedBranches.isEmpty() || (isSyncControl() && (trackedBranches.size() < myRepositories.size())))) {
      GitRepository repo = noTrackedBranch.get(0);
      notifyNoTrackedBranchError(repo, currentBranches.get(repo));
      return null;
    }
    else {
      for (GitRepository repo : noTrackedBranch) {
        mySkippedRoots.put(repo, "no tracked branch");
      }
    }

    return trackedBranches;
  }

  private static void notifyNoTrackedBranchError(@NotNull GitRepository repository, @NotNull GitLocalBranch currentBranch) {
    notifyImportantError(repository.getProject(), "Can't Update", getNoTrackedBranchError(repository, currentBranch.getName()));
  }

  private static void notifyDetachedHeadError(@NotNull GitRepository repository) {
    notifyImportantError(repository.getProject(), "Can't Update: No Current Branch", getDetachedHeadErrorNotificationContent(repository));
  }

  @VisibleForTesting
  @NotNull
  static String getDetachedHeadErrorNotificationContent(@NotNull GitRepository repository) {
    return "You are in 'detached HEAD' state, which means that you're not on any branch" +
           mention(repository) + "<br/>" +
           "Checkout a branch to make update possible.";
  }

  private boolean isSyncControl() {
    return GitVcsSettings.getInstance(myProject).getSyncSetting() == DvcsSyncSettings.Value.SYNC;
  }

  @VisibleForTesting
  @NotNull
  static String getNoTrackedBranchError(@NotNull GitRepository repository, @NotNull String branchName) {
    String recommendedCommand = recommendSetupTrackingCommand(repository, branchName);
    return "No tracked branch configured for branch " + code(branchName) +
    mention(repository) +
    " or the branch doesn't exist.<br/>" +
    "To make your branch track a remote branch call, for example,<br/>" +
    "<code>" + recommendedCommand + "</code>";
  }

  @NotNull
  private static String recommendSetupTrackingCommand(@NotNull GitRepository repository, @NotNull String branchName) {
    return String.format(GitVersionSpecialty.KNOWS_SET_UPSTREAM_TO.existsIn(repository) ?
                         "git branch --set-upstream-to=origin/%1$s %1$s" :
                         "git branch --set-upstream %1$s origin/%1$s", branchName);
  }

  /**
   * Check if merge is in progress, propose to resolve conflicts.
   * @return true if merge is in progress, which means that update can't continue.
   */
  private boolean isMergeInProgress() {
    LOG.info("isMergeInProgress: checking if there is an unfinished merge process...");
    final Collection<VirtualFile> mergingRoots = myMerger.getMergingRoots();
    if (mergingRoots.isEmpty()) {
      return false;
    }
    LOG.info("isMergeInProgress: roots with unfinished merge: " + mergingRoots);
    GitConflictResolver.Params params = new GitConflictResolver.Params(myProject);
    params.setErrorNotificationTitle("Can't update");
    params.setMergeDescription("You have unfinished merge. These conflicts must be resolved before update.");
    return !new GitMergeCommittingConflictResolver(myProject, myGit, myMerger, mergingRoots, params, false).merge();
  }

  /**
   * Checks if there are unmerged files (which may still be possible even if rebase or merge have finished)
   * @return true if there are unmerged files at
   */
  private boolean areUnmergedFiles() {
    LOG.info("areUnmergedFiles: checking if there are unmerged files...");
    GitConflictResolver.Params params = new GitConflictResolver.Params(myProject);
    params.setErrorNotificationTitle("Update was not started");
    params.setMergeDescription("Unmerged files detected. These conflicts must be resolved before update.");
    return !new GitMergeCommittingConflictResolver(myProject, myGit, myMerger, getRootsFromRepositories(myRepositories),
                                                   params, false).merge();
  }

  /**
   * Check if rebase is in progress, propose to resolve conflicts.
   * @return true if rebase is in progress, which means that update can't continue.
   */
  private boolean checkRebaseInProgress() {
    LOG.info("checkRebaseInProgress: checking if there is an unfinished rebase process...");
    final GitRebaser rebaser = new GitRebaser(myProject, myGit, myProgressIndicator);
    final Collection<VirtualFile> rebasingRoots = rebaser.getRebasingRoots();
    if (rebasingRoots.isEmpty()) {
      return false;
    }
    LOG.info("checkRebaseInProgress: roots with unfinished rebase: " + rebasingRoots);

    GitConflictResolver.Params params = new GitConflictResolver.Params(myProject);
    params.setErrorNotificationTitle("Can't update");
    params.setMergeDescription("You have unfinished rebase process. These conflicts must be resolved before update.");
    params.setErrorNotificationAdditionalDescription("Then you may <b>continue rebase</b>. <br/> You also may <b>abort rebase</b> to restore the original branch and stop rebasing.");
    params.setReverse(true);
    return !new GitConflictResolver(myProject, myGit, rebasingRoots, params) {
      @Override protected boolean proceedIfNothingToMerge() {
        return rebaser.continueRebase(rebasingRoots);
      }

      @Override protected boolean proceedAfterAllMerged() {
        return rebaser.continueRebase(rebasingRoots);
      }
    }.merge();
  }
}
