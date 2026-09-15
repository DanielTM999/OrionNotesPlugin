# Orion Notes

Bloco de notas integrado à Orion IDE. Organize anotações globais ou privadas do projeto aberto e pesquise pelo título ou conteúdo.

## Recursos

- Salvamento automático durante a edição.
- Organização de notas em pastas, com suporte a arrastar e soltar.
- Áreas separadas para notas globais e notas do projeto atual.
- Pesquisa por título e conteúdo.
- Lixeira única para todas as áreas, com restauração e prazo de exclusão configurável.
- Restauração opcional das notas abertas na sessão anterior.
- Atalho `Ctrl + Alt + N` para criar uma nota.

## Requisitos

- Orion IDE 1.0.0 ou mais recente.
- Um workspace confiável. A Orion não executa plugins em workspaces abertos no modo restrito.

## Instalação

1. Acesse a página de [Releases](https://github.com/DanielTM999/OrionNotesPlugin/releases/latest) e baixe o arquivo `orion-notes-plugin-<versão>.jar`.
2. Na Orion IDE, abra `Janela > Plugin Manager` ou `Ferramentas > Plugins`.
3. Clique em **Instalar local**.
4. Selecione o arquivo JAR baixado.

Após a confirmação, o Orion Notes estará instalado e ativo. Se o painel não aparecer, clique em **Recarregar** no gerenciador de plugins.

> Não extraia o JAR. Se o navegador salvar o arquivo compactado, mantenha a extensão `.jar`.

### Instalação manual

Se preferir, copie o JAR diretamente para a pasta de plugins da Orion:

| Sistema | Pasta |
| --- | --- |
| Windows | `%APPDATA%\Orion\plugins\` |
| Linux e macOS | `~/.config/Orion/plugins/` |

Depois, use `Ferramentas > Plugins > Recarregar Plugins` ou reinicie a IDE.

## Primeiros passos

Abra `Ferramentas > Notas > Abrir notas`. O painel **Notas** será exibido à direita da IDE.

- Use os botões no topo do painel para criar uma nota ou uma pasta.
- Selecione **Global**, o projeto atual ou uma de suas pastas para escolher o destino; sem seleção, vale a preferência configurada.
- Dê dois cliques em uma nota para abri-la no editor.
- Clique com o botão direito em um item para renomear, restaurar ou mover para a lixeira.
- Arraste notas e pastas para reorganizá-las.
- Digite no campo de pesquisa para localizar texto no título ou no conteúdo das notas.

As alterações são salvas automaticamente.

## Configurações

Nas configurações da Orion, abra a página **Orion Notes** para:

- reabrir as notas da sessão anterior;
- escolher se novos itens serão criados por padrão em **Global** ou no projeto atual;
- definir por quanto tempo os itens permanecem na lixeira;
- manter os itens da lixeira indefinidamente.

Por padrão, notas abertas não são restauradas e os itens da lixeira são excluídos definitivamente após 24 horas.

## Onde as notas ficam armazenadas?

Todas as notas ficam na área de recursos compartilhados da Orion, dentro de `shared/orion-notes`. Cada nota é marcada como Global ou vinculada ao caminho normalizado de um projeto. Notas de outros projetos permanecem armazenadas, mas ficam ocultas enquanto esses projetos não estiverem abertos. Nenhum arquivo é gravado dentro do projeto nem adicionado ao controle de versão.

Antes de remover dados manualmente, feche a Orion. Para fazer backup, copie toda a pasta `orion-notes`.

## Atualização e remoção

Para atualizar, instale o JAR da nova versão pelo **Plugin Manager**. A Orion permite manter mais de uma versão instalada e escolher qual delas fica ativa.

Para desativar ou remover o plugin, selecione **Orion Notes** no **Plugin Manager** e use a ação correspondente. Remover o plugin não apaga automaticamente as notas armazenadas na área de recursos compartilhados.

## Solução de problemas

| Problema | O que verificar |
| --- | --- |
| O JAR não é aceito | Confirme que você baixou `orion-notes-plugin-<versão>.jar`, e não o código-fonte da release. |
| O plugin não aparece | Abra o Plugin Manager e clique em **Recarregar**. Se necessário, reinicie a Orion. |
| Plugins estão indisponíveis | Reabra o workspace escolhendo **Confiar**; plugins não são carregados no modo restrito. |
| O painel de notas está fechado | Use `Ferramentas > Notas > Abrir notas`. |
| O plugin falha ao iniciar | Consulte `Ferramentas > IDE Log` para ver a mensagem de erro. |

## Compilar a partir do código-fonte

Esta seção é destinada a desenvolvedores. Você precisará do JDK 25, do Maven e do código-fonte da Orion IDE em uma pasta irmã chamada `OrionIde`.

Primeiro, instale a Orion API no repositório Maven local:

```powershell
mvn -f ..\OrionIde\pom.xml -pl OrionApi -am install
```

Depois, execute os testes e empacote o plugin:

```powershell
mvn clean package -Dantrun.skip=true
```

O JAR instalável será criado em `target/orion-notes-plugin-1.0.0.jar`.
