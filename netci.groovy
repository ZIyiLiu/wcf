// Import the utility functionality.

import jobs.generation.Utilities;

def project = GithubProject

// Globals

// Map of os -> osGroup.
def osGroupMap = ['Ubuntu':'Linux',
                  'Ubuntu14.04':'Linux',
                  'Ubuntu15.10':'Linux',
                  'Debian8.2':'Linux',
                  'OSX':'OSX',
                  'Windows_NT':'Windows_NT',
                  'CentOS7.1': 'Linux',
                  'OpenSUSE13.2': 'Linux',
                  'RHEL7.2': 'Linux']
// Map of os -> nuget runtime
def targetNugetRuntimeMap = ['OSX' : 'osx.10.10-x64',
                             'Ubuntu' : 'ubuntu.14.04-x64',
                             'Ubuntu15.10' : 'ubuntu.14.04-x64',
                             'Debian8.2' : 'ubuntu.14.04-x64',
                             'CentOS7.1' : 'centos.7-x64',
                             'OpenSUSE13.2' : 'ubuntu.14.04-x64',
                             'RHEL7.2': 'rhel.7-x64']
def branchList = ['master', 'pr', 'rc2']
def osShortName = ['Windows 10': 'win10',
                   'Windows 7' : 'win7',
                   'Windows_NT' : 'windows_nt',
                   'Ubuntu14.04' : 'ubuntu14.04',
                   'OSX' : 'osx',
                   'Windows Nano' : 'winnano',
                   'Ubuntu15.10' : 'ubuntu15.10',
                   'CentOS7.1' : 'centos7.1',
                   'OpenSUSE13.2' : 'opensuse13.2',
                   'RHEL7.2' : 'rhel7.2']                  

def static getFullBranchName(def branch) {
    def branchMap = ['master':'*/master',
        'rc2':'*/release/1.0.0-rc2',
        'pr':'*/master']
    def fullBranchName = branchMap.get(branch, null)
    assert fullBranchName != null : "Could not find a full branch name for ${branch}"
    return branchMap[branch]
}

def static getJobName(def name, def branchName) {
    def baseName = name
    if (branchName == 'rc2') {
        baseName += "_rc2"
    }
    return baseName
}

def configurationGroupList = ['Debug', 'Release']

def branch = GithubBranchName

// **************************
// Utilities shared for WCF Core builds
// **************************
class WcfUtilities
{
    def wcfRepoSyncServiceCount = 0 
    
    // Outerloop jobs for WCF Core require an external server reference
    // This should be run 
    def addWcfOuterloopTestServiceSync(def job, String os, String branch, boolean isPR) { 

        // Exclude rc2 branch, since that branch will not have the sync scripts in
        if (branch.toLowerCase().contains("rc2")) {
            return 
        }

        wcfRepoSyncServiceCount++

        def operation = isPR ? "pr" : "branch"

        job.with { 
            parameters {
                stringParam('WcfServiceUri', "wcfcoresrv2.cloudapp.net/WcfService${wcfRepoSyncServiceCount}", 'Wcf OuterLoop Test Service Uri')
                stringParam('WcfRepoSyncServiceUri', "http://wcfcoresrv2.cloudapp.net/PRService${wcfRepoSyncServiceCount}/pr.ashx", 'Wcf OuterLoop Test PR Service Uri')
            }
        }
        if (os.toLowerCase().contains("windows")) {
            job.with { 
                steps {
                    batchFile(".\\src\\System.Private.ServiceModel\\tools\\scripts\\sync-pr.cmd ${operation} %WcfRepoSyncServiceUri%")
                }           
            }
        } 
        else {
            job.with { 
                steps {
                   shell("HOME=\$WORKSPACE/tempHome ./src/System.Private.ServiceModel/tools/scripts/sync-pr.sh ${operation} \$WcfRepoSyncServiceUri")
                }
            }
        }
    }
}

wcfUtilities = new WcfUtilities()

// **************************
// Define the code coverage jobs
// **************************

branchList.each { branchName -> 
    def isPR = (branchName == 'pr')
    def os = "Windows_NT"
    def configurationGroup = "Debug"
    def newJobName = "code_coverage_${os.toLowerCase()}_${configurationGroup.toLowerCase()}"
    
    // Create the new rolling job
    def newJob = job(getJobName(Utilities.getFullJobName(project, newJobName, isPR), branchName)) {
        label('windows-elevated')
    }
    
    wcfUtilities.addWcfOuterloopTestServiceSync(newJob, os, branchName, isPR)
    
    newJob.with {
        steps {
            batchFile("build.cmd /p:ShouldCreatePackage=false /p:ShouldGenerateNuSpec=false /p:OSGroup=${osGroupMap[os]} /p:ConfigurationGroup=${configurationGroup} /p:Coverage=true /p:WithCategories=\"InnerLoop;OuterLoop\"")
        }
    }

    // Set up standard options.
    Utilities.standardJobSetup(newJob, project, isPR, getFullBranchName(branchName))
    // Add code coverage report
    Utilities.addHtmlPublisher(newJob, 'bin/tests/coverage', 'Code Coverage Report', 'index.htm')
    // Archive results
    Utilities.addArchival(newJob, '**/coverage/*,msbuild.log')
    
    // Set triggers
    if (isPR)
    {
        Utilities.addGithubPRTrigger(newJob, "Code Coverage Windows ${configurationGroup}", '(?i).*test\\W+code\\W*coverage.*')
    } 
    else {
        Utilities.addPeriodicTrigger(newJob, '@daily')
    }
}

// **************************
// WCF only
// Outerloop and Innerloop against the latest dependencies on Windows. Rolling daily for debug and release
// 
// We don't need this for the time being, however, in case we do need it in the near future, preserving the 
// build command that we use to do the testing
//
// batchFile("build.cmd /p:OSGroup=${osGroupMap[os]} /p:ConfigurationGroup=${configurationGroup} /p:FloatingTestRuntimeDependencies=true /p:WithCategories=\"InnerLoop;OuterLoop\"")
// **************************


// **************************
// Define outerloop testing for OSes that can build and run.  Run locally on each machine.
// **************************

def supportedFullCycleOuterloopPlatforms = ['Windows_NT', 'Ubuntu14.04', 'OSX']
branchList.each { branchName ->
    configurationGroupList.each { configurationGroup ->
        supportedFullCycleOuterloopPlatforms.each { os ->
            def isPR = (branchName == 'pr')
            def newJobName = "outerloop_${os.toLowerCase()}_${configurationGroup.toLowerCase()}"
            def newJob = job(getJobName(Utilities.getFullJobName(project, newJobName, isPR), branchName))
            
            wcfUtilities.addWcfOuterloopTestServiceSync(newJob, os, branchName, isPR)
            
            if (osGroupMap[os] == 'Windows_NT') {
                newJob.with {
                    steps {
                        batchFile("build.cmd /p:ConfigurationGroup=${configurationGroup} /p:OSGroup=${osGroupMap[os]} /p:WithCategories=OuterLoop /p:ServiceUri=%WcfServiceUri%")
                    }
                    
                    label('windows-elevated') // on Windows, must run on this build label
                }
            } 
            else {
                newJob.with {
                    steps {
                        shell("HOME=\$WORKSPACE/tempHome ./build.sh /p:ConfigurationGroup=${configurationGroup} /p:OSGroup=${osGroupMap[os]} /p:WithCategories=OuterLoop /p:TestWithLocalLibraries=true /p:ServiceUri=\$WcfServiceUri")
                    }
                }
                
                // Set the affinity.  OS name matches the machine affinity.
                if (os == 'Ubuntu14.04') {
                    Utilities.setMachineAffinity(newJob, os, "outer-latest-or-auto")
                } 
                else {
                    Utilities.setMachineAffinity(newJob, os, 'latest-or-auto')
                }
            }

            // Disable the builds for non Windows_NT, since outerloops don't work yet
            if (os != 'Windows_NT') 
            {
                newJob.disabled(true)
            }            

            // Set up standard options.
            Utilities.standardJobSetup(newJob, project, isPR, getFullBranchName(branchName))
            // Add the unit test results
            Utilities.addXUnitDotNETResults(newJob, 'bin/tests/**/testResults.xml')
            
            // Set up appropriate triggers. PR on demand, otherwise daily
            if (isPR) {
                // Set PR trigger.
                Utilities.addGithubPRTrigger(newJob, "OuterLoop ${os} ${configurationGroup}", "(?i).*test\\W+outerloop\\W+${os}\\W+${configurationGroup}.*")
            } 
            else {
                // Set a periodic trigger
                Utilities.addPeriodicTrigger(newJob, '@daily')
            }
        }
    } 
} 

// **************************
// Define innerloop testing for OSes that can build and run.  Run locally on each machine.
// **************************

def supportedFullCycleInnerloopPlatforms = ['Windows_NT', 'Ubuntu14.04', 'CentOS7.1', 'OSX']
branchList.each { branchName ->
    configurationGroupList.each { configurationGroup ->
        supportedFullCycleInnerloopPlatforms.each { os -> 
            def isPR = (branchName == 'pr')
            def newJobName = "${os.toLowerCase()}_${configurationGroup.toLowerCase()}"
            
            def newJob = job(getJobName(Utilities.getFullJobName(project, newJobName, isPR), branchName)) 
            
            if (osGroupMap[os] == 'Windows_NT')
            {
                newJob.with {
                    steps {
                        batchFile("call \"C:\\Program Files (x86)\\Microsoft Visual Studio 14.0\\VC\\vcvarsall.bat\" x86 && build.cmd /p:ConfigurationGroup=${configurationGroup} /p:OSGroup=${osGroupMap[os]}")
                        batchFile("C:\\Packer\\Packer.exe .\\bin\\build.pack .\\bin")
                    }
                }
            } 
            else {
                newJob.with {
                    steps {
                        shell("HOME=\$WORKSPACE/tempHome ./build.sh /p:ShouldCreatePackage=false /p:ShouldGenerateNuSpec=false /p:OSGroup=${osGroupMap[os]} /p:ConfigurationGroup=${configurationGroup}")
                    }
                }
            }
            
            // Disable the builds for CentOS because it doesn't fully work yet
            if (os == 'CentOS7.1') 
            {
                newJob.disabled(true)
            }

            // Set the affinity.  All of these run on Windows currently.
            Utilities.setMachineAffinity(newJob, os, 'latest-or-auto')
            // Set up standard options.
            Utilities.standardJobSetup(newJob, project, isPR, getFullBranchName(branchName))
            // Add the unit test results
            Utilities.addXUnitDotNETResults(newJob, 'bin/tests/**/testResults.xml')
            // Add archival for the built data
            if (osGroupMap[os] == 'Windows_NT') {
                Utilities.addArchival(newJob, "bin/build.pack,bin/${osGroupMap[os]}.AnyCPU.${configurationGroup}/**,bin/ref/**,bin/packages/**,msbuild.log")
            } 
            else {
                Utilities.addArchival(newJob, "bin/${osGroupMap[os]}.AnyCPU.${configurationGroup}/**,bin/ref/**,bin/packages/**,msbuild.log")
            }
            
            // Set up triggers
            if (isPR) {
                // Set PR trigger.
                Utilities.addGithubPRTrigger(newJob, "Innerloop ${os} ${configurationGroup} Build and Test")
            } 
            else {
                // Set a push trigger
                Utilities.addGithubPushTrigger(newJob)
            }
        }
    }
}
