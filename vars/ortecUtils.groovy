void prepareVM(String vmName, String cleanSnapshotName) {
    if (cleanSnapshotName == '') {
        cleanSnapshotName = 'JenkinsClean'    
    }
    vSphere buildStep: [$class: 'RevertToSnapshot', snapshotName: cleanSnapshotName, vm: vmName], serverName: 'HillView'
    
    for (slave in Hudson.instance.slaves) {
        if (slave.computer.name == vmName) { // TODO check capitalization; the VMname and slavename are not the same thing after all..
            echo 'disconnecting ' + slave.computer.name
            slave.computer.disconnect()            
        }
    }
    
    vSphere buildStep: [$class: 'PowerOn', timeoutInSeconds: 180, vm: vmName], serverName: 'HillView'
}

String mountWorkspace(String mountPoint) {
    if (mountPoint == '') {
        mountPoint = '/src2'
    }
    def wscygwin = env.WORKSPACE.replaceAll("\\\\","/")
    
    def cygwincmd = 'echo \'' + wscygwin + ' ' + mountPoint + ' ntfrs text,noacl,posix=0,user 0 0\' >> /etc/fstab ; mount -a ; '
    executeInCygwin(cygwincmd, '') // TODO check if succeeded...
    return mountPoint
}

int executeInCygwin(String bashScript, String location) {
    if (location == '') {
        location = '/'  // I tried ~ , but apparently the user is SYSTEM... Still the case with better mount? TODO fix! 
    }
    def cygwincmd = 'PATH=/usr/bin:$PATH ; cd ' + location + ' ; ' + bashScript
    bat 'C:\\cygwin\\bin\\bash.exe -c "' + cygwincmd + '"'
    return 0 // TODO somehow return something relevant... stdout, stderr, err, return code?
}

void syncSource(String clientSpecDepotLocation) {
    def workspaceName = "jenkins-${NODE_NAME}-${JOB_NAME}"
    def clientSpecWorkspaceName = workspaceName + '-clientspec'

    echo 'Retrieving clientspec from Perforce...'
    checkout([
        $class: 'PerforceScm', 
        credential: 'ebfe3457-08af-4b78-8822-33364e7c5c95', 
        populate: [
            $class: 'AutoCleanImpl', // TODO we dont have to use this populate option here maybe? we only care about getting the right spec.txt
            delete: false, 
            modtime: false, 
            parallel: [enable: false, minbytes: '1024', minfiles: '1', path: '/usr/local/bin/p4', threads: '4'], 
            pin: '', 
            quiet: true, 
            replace: true
        ], 
        workspace: [
            $class: 'ManualWorkspaceImpl', 
            charset: 'none', 
            name: clientSpecWorkspaceName,
            pinHost: false,
            spec: [ view: clientSpecDepotLocation + ' //' + clientSpecWorkspaceName + '/spec.txt' ]
        ]
    ])

    def clientSpec = readFile file:'spec.txt'
    mapping = clientSpec.replaceFirst(/(?ms).*^[Vv]iew:/, "")     // remove everything before and including View:
    mapping = mapping.replaceAll(/(?m)^[ \t]*/, "")               // remove leading whitespace
    mapping = mapping.replaceAll(/(?m)#.*$/, "")                  // remove comments
    mapping = mapping.replaceAll(/(?m)^\r?\n/, "")                // remove empty lines
    mapping = mapping.replaceAll('%COMPUTERNAME%', workspaceName) // fill in the workspace placeholder
    echo 'mapping: ' + mapping
    
    echo 'Syncing from Perforce...'
    checkout([
        $class: 'PerforceScm', 
        credential: 'ebfe3457-08af-4b78-8822-33364e7c5c95', 
        populate: [
            $class: 'AutoCleanImpl', 
            delete: true, 
            modtime: false, 
            parallel: [enable: false, minbytes: '1024', minfiles: '1', path: '/usr/local/bin/p4', threads: '4'], 
            pin: '', 
            quiet: true, 
            replace: true
        ], 
        workspace: [
            $class: 'ManualWorkspaceImpl', 
            charset: 'none', 
            name: workspaceName,
            pinHost: false,
            spec: [ view: mapping ]
        ]
    ])
}
