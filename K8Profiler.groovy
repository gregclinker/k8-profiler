import groovy.json.JsonSlurper
import groovy.yaml.YamlBuilder
import groovy.yaml.YamlSlurper

class K8Profiler {
    private static final double requestFactor = 1.2
    private static final double limitsFactor = 1.5
    private String profileToApply
    private String prometheusHost
    private Map podDeploymentMap
    private Map cpuPrometheusPodData
    private Map memoryPrometheusPodData
    private boolean debugOn = false
    private boolean test = false
    private boolean dryrun = false

    static void main(String[] args) {
        def profiler = new K8Profiler()
        def lastArg = null
        args.each { String arg ->
            if (arg == "--help" || arg == "-h") {
                println ""
                println "Usage: K8profiler --test --debug --dryrun --prometheushost 35.230.139.105 --profiletoapply profile.yaml"
                println ""
                println "Examples:"
                println ""
                println "Use built in test data - groovy K8profiler.groovy --test --debug"
                println ""
                println "Generate optimised profile against data in given Prometheus - groovy K8Profiler.groovy --prometheushost 35.230.139.105"
                println ""
                println "Apply the profile - groovy K8Profiler.groovy --profiletoapply profile.yaml"
                println ""
                System.exit(0)
            } else if (arg == "--test") {
                profiler.test = true
            } else if (arg == "--debug") {
                profiler.debugOn = true
            } else if (arg == "--dryrun") {
                profiler.dryrun = true
            }
            if (lastArg == "--prometheushost") {
                profiler.prometheusHost = arg
            } else if (lastArg == "--profiletoapply") {
                profiler.profileToApply = arg
            }
            lastArg = arg
        }

        if (profiler.profileToApply) {
            profiler.applyProfile()
        } else {
            profiler.generateProfile()
        }
    }

    private void applyProfile() {
        Map map = new YamlSlurper().parse(profileToApply as File)
        Map deployments = map.profile
        deployments.keySet().each { String name ->
            Map profile = deployments[name]
            def app = profile["app"]
            def commandToRun = "kubectl scale ${profile["kind"].toString().toLowerCase()} ${app} --replicas ${profile["replicas"]}"
            println commandToRun
            if (!dryrun) {
                command(commandToRun)
            }
            List containers = profile["containers"]
            containers.forEach { Map container ->
                Map requests = container["resources"]["requests"]
                Map limits = container["resources"]["limits"]
                def requestsValue = "cpu=${requests["cpu"]},memory=${requests["memory"]}"
                def limitsValue = "cpu=${limits["cpu"]},memory=${limits["memory"]}"
                commandToRun = "kubectl set resources ${profile["kind"].toString().toLowerCase()} ${app} -c ${container["name"]} --requests ${requestsValue} --limits ${limitsValue}"
                println commandToRun
                if (!dryrun) {
                    command(commandToRun)
                }
            }
        }
    }

    private void generateProfile() {
        // load the data test or real
        loadData()
        // get the profile
        Map profileMap = calculate()
        double totalCpuSaving = 0.0
        double totalMemorySaving = 0.0
        profileMap.keySet().each { String name ->
            List containers = profileMap[name]["containers"]
            containers.each { Map container ->
                totalCpuSaving += toDouble(container["optimisation"]["cpuSaving"])
                totalMemorySaving += toDouble(container["optimisation"]["memorySaving"])
            }
        }
        // output
        createOutput(totalCpuSaving, totalMemorySaving, profileMap)
    }

    private void createOutput(double totalCpuSaving, double totalMemorySaving, LinkedHashMap<Object, Object> profileMap) {
        Map completeMap = ["description": "test profile", "optimisation": ["totalCpuSaving": prettyCpu(totalCpuSaving), "totalMemorySaving": prettyMemory(totalMemorySaving)], "profile": profileMap]
        def yamlBuilder = new YamlBuilder()
        yamlBuilder.call(completeMap)
        println yamlBuilder.toString()
    }

    private void loadData() {
        if (test) {
            cpuPrometheusPodData = ["fake-load-1": ["fake-load": 0.10323440528939357, "istio": 0.01], "fake-load-2": ["fake-load": 0.24872855889624348, "istio": 0.02], "fake-load-3": ["fake-load": 0.24872855889624348]]
            memoryPrometheusPodData = ["fake-load-1": ["fake-load": 242917376, "istio": 42917376], "fake-load-2": ["fake-load": 460959744, "istio": 231736], "fake-load-3": ["fake-load": 460959744]]
        } else {
            podDeploymentMap = matchPodsToDeployments()
            cpuPrometheusPodData = matchPrometheusPodDatatpDeployments('max(max_over_time(irate(container_cpu_usage_seconds_total{namespace="fake-load",pod=~"fake-load.*",container=~"fake-load.*"}[2m])[15m:1m]))by(container,pod)')
            println cpuPrometheusPodData
            memoryPrometheusPodData = matchPrometheusPodDatatpDeployments('max(max_over_time(container_memory_usage_bytes{namespace="fake-load",pod=~"fake-load.*",container=~"fake-load.*"}[15m:1m]))by(pod,container)')
        }
    }

    private Map calculate() {
        Map kubectlMap
        if (test) {
            kubectlMap = new JsonSlurper().parse(new File("deployment-test.json"))
        } else {
            kubectlMap = new JsonSlurper().parseText(command("kubectl get deploy,sts -ojson").toString())
        }
        List items = kubectlMap.items
        Map deploymentsMap = [:]
        items.forEach { Map item ->
            def kind = item.kind
            def name = item.metadata.name
            def app = item.metadata.labels.app
            def replicas = item.spec.replicas
            if (kind && name && app) {
                List containers = []
                (item.spec.template.spec.containers as List).each { Map container ->
                    containers.add(["name": container.name, "resources": container.resources])
                }
                Map deploymentMap = ["kind": kind, "app": app, "replicas": replicas, "containers": containers]
                optimiseDeployment(name, deploymentMap)
                deploymentsMap[name] = deploymentMap
            }
        }
        deploymentsMap
    }

    private void optimiseDeployment(String name, Map deployment) {
        debug("optimisiing deplyment ${deployment.toString()}")
        List deploymentContainers = deployment["containers"]
        Map containerMemoryUsageMap = memoryPrometheusPodData[name]
        Map containerCpuUsageMap = cpuPrometheusPodData[name]
        debug("using cpuPrometheusPodData=${cpuPrometheusPodData}, memoryPrometheusPodData=${memoryPrometheusPodData}")
        List newDeploymentContainers = []
        containerMemoryUsageMap.keySet().each { container ->
            Map deploymentResources = null
            deploymentContainers.each {
                if (it["name"] == container) {
                    deploymentResources = it["resources"]
                }
            }
            debug("containerCpuUsageMap ${containerCpuUsageMap}, containerMemoryUsageMap ${containerMemoryUsageMap}")
            debug("before optimisation ${deploymentResources}")
            double deploymentMemoryLimit = toDouble(deploymentResources["limits"]["memory"])
            double deploymentCpuLimit = toDouble(deploymentResources["limits"]["cpu"])
            double memoryUsage = toDouble(containerMemoryUsageMap[container].toString())
            double cpuUsage = toDouble(containerCpuUsageMap[container].toString())
            double optimisedMemoryLimit = memoryUsage * limitsFactor
            double optimisedCpuLimit = cpuUsage * limitsFactor
            Map optimisation = ["cpuSaving": prettyCpu(deploymentCpuLimit - optimisedCpuLimit), "memorySaving": prettyMemory(deploymentMemoryLimit - optimisedMemoryLimit)]
            Map optimisedResources = [
                    "requests": ["cpu": prettyCpu(cpuUsage * requestFactor), "memory": prettyMemory(memoryUsage * requestFactor)],
                    "limits"  : ["cpu": prettyCpu(optimisedCpuLimit), "memory": prettyMemory(optimisedMemoryLimit)]
            ]
            debug("after optimisation ${optimisedResources}")
            newDeploymentContainers.add(["name": container, "resources": optimisedResources, "optimisation": optimisation])
        }
        deployment["containers"] = newDeploymentContainers
    }

    private static double toDouble(String value) {
        if (value =~ "Gi") {
            return Double.valueOf(value.replace("Gi", "")) * 1024 * 1024 * 1024
        } else if (value =~ "Mi") {
            return Double.valueOf(value.replace("Mi", "")) * 1024 * 1024
        } else if (value =~ "m") {
            return Double.valueOf(value.replace("m", "")) / 1000
        }
        return Double.valueOf(value)
    }

    private static String prettyMemory(double value) {
        def mb = value / (1024 * 1024)
        if (mb > 1024) {
            return sprintf("%.2fGi", mb / 1024)
        }
        return sprintf("%.0fMi", mb)
    }

    private static String prettyCpu(double value) {
        if (value < 1.0) {
            return sprintf("%.0fm", (value * 1000))
        }
        return sprintf("%.2f", value)
    }

    private Map matchPodsToDeployments() {
        Map kubectlMap = new JsonSlurper().parseText(command("kubectl get pods -ojson").toString())
        //Map kubectlMap = new JsonSlurper().parse(new File("pods-test.json"))
        List items = kubectlMap.items
        Map podAppMap = [:]
        items.forEach { Map item ->
            def name = item.metadata.name
            def app = item.metadata.labels.app
            if (name && app) {
                podAppMap[name] = app
            }
        }
        podAppMap
    }

    private Map matchPrometheusPodDatatpDeployments(String query) {
        Map cpuUsageMap = prometheusQuery(query)
        List metrics = cpuUsageMap.data.result as List
        Map podData = [:]
        metrics.each { Map metricsMap ->
            def container = metricsMap.metric.container
            def pod = metricsMap.metric.pod
            def app = podDeploymentMap[pod]
            def value = metricsMap.value[1]
            if (container != null && pod != null && value != null) {
                if (podData[app] == null) {
                    podData[app] = [:]
                }
                podData[app][container] = value
            }
        }
        podData
    }

    private Map prometheusQuery(String query) {
        def get = new URL("http://${prometheusHost}:8080/api/v1/query?query=${query}").openConnection()
        debug("executing prometheus query ${get.getURL()}")
        def getRC = get.getResponseCode()
        debug("got http $getRC")
        if (getRC.equals(200)) {
            def text = get.getInputStream().getText()
            return new JsonSlurper().parseText(text)
        } else {
            throw new Exception("promethus query ${query} failed, http=${getRC}")
        }
    }

    private String command(String command) {
        def sout = new StringBuilder(), serr = new StringBuilder()
        def proc = command.execute()
        proc.consumeProcessOutput(sout, serr)
        proc.waitForOrKill(5000)
        if (serr) {
            throw new Exception("error executing command $command\n$serr")
        } else {
            return sout
        }
        return null
    }

    private void debug(String text) {
        if (debugOn) {
            println "DEBUG $text"
        }
    }
}
