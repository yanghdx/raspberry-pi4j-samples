# JVM-aware languages

### Quick preamble
Java runs on the Raspberry PI.
One big asset of Java is its portability, its "write-once-run-everywhere" feature.
Running Java means that there is a Java Virtual Machine (JVM) that can take a `.class` file and execute it.
The JVM does not care about the machine or OS a `.class` has been generated (compiled) on, it will just work if it is valid at least somewhere.

Actually, the portability of the code is taken care of by the JVM. Porting the JVM is the responsability of the JVM vendor, not of the author of the code itself.

A `.class` file contains _byte code_. To see what the _byte code_ looks like, you can use `javap` with its `-c` flag:
```
$ javap -c -cp ./build/classes/main ./build/classes/main/mainRPi.class
Compiled from "mainRPi.groovy"
public class mainRPi extends groovy.lang.Script {
  public static transient boolean __$stMC;

  public mainRPi();
    Code:
       0: aload_0
       1: invokespecial #14                 // Method groovy/lang/Script."<init>":()V
       4: invokestatic  #18                 // Method $getCallSiteArray:()[Lorg/codehaus/groovy/runtime/callsite/CallSite;
       7: astore_1
       8: return

  public mainRPi(groovy.lang.Binding);
    Code:
       0: invokestatic  #18                 // Method $getCallSiteArray:()[Lorg/codehaus/groovy/runtime/callsite/CallSite;
       3: astore_2
       4: aload_0
       5: aload_1
       6: invokespecial #23                 // Method groovy/lang/Script."<init>":(Lgroovy/lang/Binding;)V
       9: return

  public static void main(java.lang.String...);
    Code:
       0: invokestatic  #18                 // Method $getCallSiteArray:()[Lorg/codehaus/groovy/runtime/callsite/CallSite;
       3: astore_1
       4: aload_1
       5: ldc           #28                 // int 0
  ...
```
 
This does not look at all like Java, does it?

From a Java file (with a `.java` extension) you get a `.class` file by using the `javac` compiler.

But here is the trick: you can come up with your own language, and if you can write your own compiler that turns your 
language-specific files into `.class` files supported by the JVM (a library like [ASM](http://asm.ow2.org/) can help you with that), 
you can then take advantage of the features of the JVM, big ones of them being its portability and interoperability. No need to mention 
that it can use at runtime `jars` and other `class` files, whatever language they have originally been written in.

Now, not only a given `.class` can be executed across platforms, but also it can come from several different languages. 

That is - in very short - what those JVM-aware languages are doing. 
We have here snippets of Scala, Groovy, and Kotlin. The list is not closed, by far. Many other such JVM-aware languages exist, and will exist. 
 
### A quick note
To know how to install those languages on the Raspberry PI (or wherever you want), use any search engine you like. It's out of the scope of this document ;)
 
## First
Before doing what is described below, run a build of the project:
 ```
 $ cd OtherJVM.languages
 $ ../gradlew build
 :I2C.SPI:compileJava UP-TO-DATE
 :I2C.SPI:processResources UP-TO-DATE
 :I2C.SPI:classes UP-TO-DATE
 :I2C.SPI:copyResources UP-TO-DATE
 :I2C.SPI:jar UP-TO-DATE
 :Serial.IO:compileJava UP-TO-DATE
 :Serial.IO:compileScala UP-TO-DATE
 :Serial.IO:processResources UP-TO-DATE
 :Serial.IO:classes UP-TO-DATE
 :Serial.IO:jar UP-TO-DATE
 :MindWave:compileJava UP-TO-DATE
 :MindWave:compileScala UP-TO-DATE
 :MindWave:processResources UP-TO-DATE
 :MindWave:classes UP-TO-DATE
 :MindWave:jar UP-TO-DATE
 :SevenSegDisplay:compileJava UP-TO-DATE
 :SevenSegDisplay:processResources UP-TO-DATE
 :SevenSegDisplay:classes UP-TO-DATE
 :SevenSegDisplay:copyResources UP-TO-DATE
 :SevenSegDisplay:jar UP-TO-DATE
 :OtherJVM.languages:compileKotlin UP-TO-DATE
 :OtherJVM.languages:compileJava UP-TO-DATE
 :OtherJVM.languages:compileGroovy UP-TO-DATE
 :OtherJVM.languages:compileScala UP-TO-DATE
 :OtherJVM.languages:processResources UP-TO-DATE
 :OtherJVM.languages:classes UP-TO-DATE
 :OtherJVM.languages:jar UP-TO-DATE
 :OtherJVM.languages:assemble UP-TO-DATE
 :OtherJVM.languages:compileTestKotlin UP-TO-DATE
 :OtherJVM.languages:compileTestJava UP-TO-DATE
 :OtherJVM.languages:compileTestGroovy UP-TO-DATE
 :OtherJVM.languages:compileTestScala UP-TO-DATE
 :OtherJVM.languages:processTestResources UP-TO-DATE
 :OtherJVM.languages:testClasses UP-TO-DATE
 :OtherJVM.languages:test UP-TO-DATE
 :OtherJVM.languages:check UP-TO-DATE
 :OtherJVM.languages:build UP-TO-DATE
 
 BUILD SUCCESSFUL
 
 Total time: 32.911 secs
 $
 ```
 
## Scala 
Several examples are provided, along with the way to run them, from a shell, or from Gradle:
```
$ cd scripts/scala
$ ./hello
Compiling
Now running
Hello, Scalaspberry world! 
$
$ cd ../.. # Back at the project root
$ ../gradlew runHelloActor
 ...
:OtherJVM.languages:classes UP-TO-DATE
:OtherJVM.languages:runHelloActor
hello back at you!
Unexpected String: buenos dias
Whatever message : Watafok
Got an un-managed class Bullshit : Bullshit(Moo!)
>> Result is: Miom Miom Miom Miom 

BUILD SUCCESSFUL

Total time: 4.628 secs
$

```


### Scala REPL
Scala comes with a REPL (Read-Execute-Print-Loop). A REPL behaves like an interpreter, and is _very_ convenient.

From the project's root, you can type the scala commands in the Scala REPL:
```
 $ cd scala.worksheets
 $ sudo scala
Welcome to Scala version 2.11.6 (Java HotSpot(TM) Client VM, Java 1.8.0_101).
Type in expressions to have them evaluated.
Type :help for more information.

scala> :require /opt/pi4j/lib/pi4j-core.jar
Added '/opt/pi4j/lib/pi4j-core.jar' to classpath.

scala> :require ../../I2C.SPI/build/libs/I2C.SPI-1.0.jar
Added '/home/pi/raspberry-pi4j-samples/OtherJVM.languages/scala.worksheets/../../I2C.SPI/build/libs/I2C.SPI-1.0.jar' to classpath.

scala> import com.pi4j.system.SystemInfo
import com.pi4j.system.SystemInfo

scala> import i2c.sensor.BME280
import i2c.sensor.BME280

scala> val bme280  = new BME280
bme280: i2c.sensor.BME280 = i2c.sensor.BME280@1820e51

scala> try {
     |   val temp  = bme280.readTemperature
     |   val press = bme280.readPressure / 100
     |   val hum = bme280.readHumidity
     |   println(s"CPU Temperature   :  ${SystemInfo.getCpuTemperature}\u00baC")
     |   println(s"Temp:${temp}\u00baC, Press:${press} hPa, Hum:${hum} %")
     | } catch {
     |   case ex: Exception => {
     |     println(ex.toString)
     |   }
     | }
CPU Temperature   :  49.4ºC
Temp:19.920628ºC, Press:1021.1498 hPa, Hum:70.61513 %

scala>
```
or more easily, just invoke already written scala worksheets with the `:load` REPL command:
```
$ cd scala.worksheets
$ sudo scala
Welcome to Scala version 2.11.6 (Java HotSpot(TM) Client VM, Java 1.8.0_101).
Type in expressions to have them evaluated.
Type :help for more information.

scala> :load set.cp.sc
scala> :load sensor.reader.sc
CPU Temperature   :  49.4ºC
Temp:19.920628ºC, Press:1021.1498 hPa, Hum:70.61513 %

scala>
```

## Groovy on Pi
This is a small Groovy project that shows how to use Java classes written for the `Raspberry PI`
from a Groovy script.

Groovy is similar to Scala in the sense that
* It runs on a JVM
* It knows what a `.class` file is, whatever language it has been compiled from.

Those interested will also take a look at the Java part of the project, showing how to invoke scripting languages (Groovy, JavaScript) from Java,
using the features of the JSR223. Do check it out, it's worth it.

### Read a BME280 from groovy
From the project's root:
```
$ ./scripts/groovy/run
==========
Now running some RPi stuff
Temperature: 20.87 C
Pressure   : 1017.97 hPa
Humidity   : 66.91 %
CPU Temperature   :  47.8
CPU Core Voltage  :  1.325
```

### Groovy Console
Groovy comes with a graphical console (not exactly a REPL...) that can be launched _from a graphical desktop_ using
the `groovyConsole` command. You type your commands in the top pane, and then you execute them by hitting the 
`Execute Groovy Script` button:
![Groovy console](./RPiDesktop.png)

## Kotlin
Among others, Kotlin come with tools like `kotlinc` and `kotlinc-jvm`.

See below how to:
- Compile a Kotlin file
- Run it
- Run a Kotlin Script
- How to use the Kotlin REPL

After installing `kotlinc` as explained [here](https://kotlinlang.org/docs/tutorials/command-line.html),
from the project's root directory:

#### Compilation
```
$ cd src/kotlin
$ kotlinc KotlinSensors.kt -cp ../../../I2C.SPI/build/classes/main/ -include-runtime -d sensors.jar
```

#### Execution
There is a script named `runSensor`:
```
#!/bin/bash
#
# Reads a sensor from Kotlin
#
PI4J_HOME=/opt/pi4j
CP=../../../../I2C.SPI/build/classes/main/
CP=$CP:../../src/kotlin/sensors.jar
CP=$CP:$PI4J_HOME/lib/pi4j-core.jar
#
# echo $CP
#
sudo java -cp $CP KotlinSensorsKt
```
Execute it:
```
$ cd script/kotlin
$ ./runSensor 
Temp:23.418814 ºC, Press:1018.09607 hPa, Hum:64.762695 %
```

#### Run a Kotlin Script (`.kts`)
```
$ cd src/kotlin
$ kotlinc -script hello.kts
Hello Kotlin World!
$
```

Also:
```
$ cd src/kotlin
$ PI4J_HOME=/opt/pi4j
$ CP=../../../I2C.SPI/build/classes/main/
$ CP=$CP:$PI4J_HOME/lib/pi4j-core.jar
$ sudo `which kotlinc` -cp $CP -script sensors.kts
Temp= 23.80598 ºC
$ 
```

#### REPL
Kotlin come with a REPL (Read-Execute-Print-Loop), like Scala:
```
# From the project's root
#
$ PI4J_HOME=/opt/pi4j
$ CP=../I2C.SPI/build/classes/main/
$ CP=$CP:$PI4J_HOME/lib/pi4j-core.jar
$ sudo `which kotlinc-jvm` -cp $CP
Welcome to Kotlin version 1.0.3 (JRE 1.8.0_65-b17)
Type :help for help, :quit for quit
>>> import i2c.sensor.BME280
>>> val bme280 = BME280()
>>> val temp = bme280.readTemperature()
>>> println("Temp= $temp \u00baC")
Temp= 23.80598 ºC
>>> :quit
$ 
```

Cool, hey?