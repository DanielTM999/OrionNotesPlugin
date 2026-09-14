# Orion Notes Plugin

Bloco de notas persistente para a Orion IDE. As notas ficam no Resource compartilhado da IDE, em `shared/orion-notes`, e nunca dentro do projeto aberto.

## Build

Instale primeiro a OrionApi localmente e depois empacote o plugin:

```powershell
mvn -f ..\OrionIde\pom.xml -pl OrionApi -am install
mvn clean package
```

O JAR instalavel sera criado em `target/orion-notes-plugin-1.0.0.jar`.
