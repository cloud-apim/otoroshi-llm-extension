# Contributing to otoroshi-llm-extension

These guidelines apply to all projects living in the the `cloud-apim/otoroshi-llm-extension` repository.

These guidelines are meant to be a living document that should be changed and adapted as needed.
We encourage changes that make it easier to achieve our goals in an efficient way.

## Codebase

* [src](https://github.com/cloud-apim/otoroshi-llm-extension/src): contains the otoroshi-llm-extension sources and tests

## Workflow

The steps below describe how to get a patch into a main development branch (e.g. `maon`). 
The steps are exactly the same for everyone involved in the project (be it core team, or first time contributor).
We follow the standard GitHub [fork & pull](https://help.github.com/articles/using-pull-requests/#fork--pull) approach to pull requests. Just fork the official repo, develop in a branch, and submit a PR!

1. To avoid duplicated effort, it might be good to check the [issue tracker](https://github.com/cloud-apim/otoroshi-llm-extension/issues) and [existing pull requests](https://github.com/cloud-apim/otoroshi-llm-extension/pulls) for existing work.
   - If there is no ticket yet, feel free to [create one](https://github.com/cloud-apim/otoroshi-llm-extension/issues/new) to discuss the problem and the approach you want to take to solve it.
1. [Fork the project](https://github.com/cloud-apim/otoroshi-llm-extension#fork-destination-box) on GitHub. You'll need to create a feature-branch for your work on your fork, as this way you'll be able to submit a pull request against the mainline otoroshi-llm-extension.
1. Create a branch on your fork and work on the feature. For example: `git checkout -b wip-awesome-new-feature`
   - Please make sure to follow the general quality guidelines (specified below) when developing your patch.
   - Please write additional tests covering your feature and adjust existing ones if needed before submitting your pull request. 
1. Once your feature is complete, prepare the commit with a good commit message, for example: `Adding nice wasm feature #42` (note the reference to the ticket it aimed to resolve).
1. Now it's finally time to [submit the pull request](https://help.github.com/articles/using-pull-requests)!
    - Please make sure to include a reference to the issue you're solving *in the comment* for the Pull Request, this will cause the PR to be linked properly with the Issue. Examples of good phrases for this are: "Resolves #1234" or "Refs #1234".
1. Now both committers and interested people will review your code. This process is to ensure the code we merge is of the best possible quality, and that no silly mistakes slip through. You're expected to follow-up these comments by adding new commits to the same branch. The commit messages of those commits can be more loose, for example: `Removed debugging using printline`, as they all will be squashed into one commit before merging into the main branch.
    - The community and team are really nice people, so don't be afraid to ask follow up questions if you didn't understand some comment, or would like clarification on how to continue with a given feature. We're here to help, so feel free to ask and discuss any kind of questions you might have during review!
1. After the review you should fix the issues as needed (pushing a new commit for new review etc.), iterating until the reviewers give their thumbs up–which is signalled usually by a comment saying `LGTM`, which means "Looks Good To Me". 
1. If the code change needs to be applied to other branches as well (for example a bugfix needing to be backported to a previous version), one of the team will either ask you to submit a PR with the same commit to the old branch, or do this for you.
1. Once everything is said and done, your pull request gets merged. You've made it!

The TL;DR; of the above very precise workflow version is:

1. Fork otoroshi-llm-extension
2. Hack and test on your feature (on a branch)
3. Document it 
4. Submit a PR
6. Keep polishing it until received thumbs up from the core team
7. Profit!

## External dependencies

All the external runtime dependencies for the project, including transitive dependencies, must have an open source license that is equal to, or compatible with, [Apache 2](http://www.apache.org/licenses/LICENSE-2.0).

This must be ensured by manually verifying the license for all the dependencies for the project:

1. Whenever a committer to the project changes a version of a dependency (including Scala) in the build file.
2. Whenever a committer to the project adds a new dependency.
3. Whenever a new release is cut (public or private for a customer).

Which licenses are compatible with Apache 2 are defined in [this doc](http://www.apache.org/legal/3party.html#category-a), where you can see that the licenses that are listed under ``Category A`` are automatically compatible with Apache 2, while the ones listed under ``Category B`` need additional action:

> Each license in this category requires some degree of [reciprocity](http://www.apache.org/legal/3party.html#define-reciprocal); therefore, additional action must be taken in order to minimize the chance that a user of an Apache product will create a derivative work of a reciprocally-licensed portion of an Apache product without being aware of the applicable requirements.

Each project must also create and maintain a list of all dependencies and their licenses, including all their transitive dependencies. This can be done either in the documentation or in the build file next to each dependency.

## Tests

Every new feature should provide corresponding tests to ensure everything is working and will still working in future releases. To run the tests, just run

```sh
sbt test
```