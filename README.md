# ApiCommonWebService
Plugins to execute web services under the wadl-based Web Service infrastructure <a href="https://github.com/VEuPathDB/WSF">WSF</a> common to the VEuPathDB genomics sites.

Additionally, the CLI implementation of the HighSpeedSnpSearch system that is called by the [High Speed Snp Search plugin](WSFPlugin/src/main/java/org/apidb/apicomplexa/wsfplugin/highspeedsnpsearch)

## Dependencies

   + ant
   + Perl 5
   + Java 11+
   + External dependencies: see [pom.xml](pom.xml)
   + environment variables for GUS_HOME and PROJECT_HOME
   + Internal Dependencies: see [build.xml](build.xml)

## Installation instructions.

   + bld ApiCommonWebService

## Manifest

   + WSFPlugin/config :: configuration for individual plugins
   + WSFPlugin/lib :: conifer and perl libraries for the plugins
   + WSFPlugin/src :: java source code for the plugins
   + HighSpeedSnpSearch/bin :: executables for HSSS
   + HighSpeedSnpSearch/doc :: docs for HSSS
   + HighSpeedSnpSearch/lib/perl :: perl libraries for HSSS
   + HighSpeedSnpSearch/src/c :: C src for HSSS
   + HighSpeedSnpSearch/test :: tests for HSSS
