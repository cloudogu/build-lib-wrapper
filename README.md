# Build Lib Wrapper Pipeline

Dieses Repository enthält eine Jenkins Shared Library namens **build-lib-wrapper**, die zentrale Pipeline-Schritte für Dogu-Anwendungen kapselt. Mithilfe dieser Bibliothek können viele Applikationen mit einer einheitlichen Pipeline bedient werden – Änderungen an der Pipeline-Logik oder Versionsupdates der zugrunde liegenden Libraries müssen dann nur zentral vorgenommen werden.

---

## Verwendung in der Jenkinsfile

Verwenden Sie in Ihrer Jenkinsfile folgende Imports, um die globale Bibliothek und die abhängigen Libraries zu laden:

```groovy
#!groovy
@Library([
  'github.com/cloudogu/build-lib-wrapper@release',
  'ces-build-lib',
  'dogu-build-lib'
]) _

// Now call the sharedBuildPipeline function with your custom configuration.
sharedBuildPipeline([
    // Required parameter
    doguName: "postfix",
    
    // Optional parameters – override defaults here
    preBuildAgent       : 'docker',
    buildAgent          : 'vagrant',
    doguDirectory       : "/dogu",
    namespace           : "official",
    
    // Credentials and git information
    gitUser             : "mmustermann",
    committerEmail      : "mmustermann@cloudogu.com",
    gcloudCredentials   : "gcloud-mmustermann",
    sshCredentials      : "jenkins-gcloud-mmustermann",
    backendUser         : "mmustermann-setup",
    
    // Additional options
    updateSubmodules    : false,
    shellScripts        : "./resources/logging.sh ./resources/startup.sh ./resources/mask2cidr.sh",
    dependencies        : ["nginx"],
    checkMarkdown       : true,
    runIntegrationTests : false,
    cypressImage        : "cypress/included:13.15.2",
    upgradeCypressImage : "cypress/included:13.2.0"
])
```

Falls Sie eine neue Version testen wollen, können Sie die Version der Library auch direkt überschreiben:

```groovy
// Standardimport (globale Version)
@Library([
  'github.com/cloudogu/build-lib-wrapper@release',
  // ces & dogulib werden implizit über https://ecosystem.cloudogu.com/jenkins/manage/configure Global Trusted Pipeline Libraries default version geladen
]) _

// Testen einer neuen Version mit Überschreibung
@Library([
  'github.com/cloudogu/build-lib-wrapper@develop',
  'ces-build-lib@4.1.0' // default Version wird hier überschrieben
]) _
```