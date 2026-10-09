# Margem — spec do produto

Status: **decidido** em 2026-10-09. O que está em §10 espera resposta, uma por vez.
Escopo: só produto (o quê e por quê). Implementação: [architecture.md](architecture.md).
Vocabulário: [glossary.md](glossary.md). Identidade visual: [design_system.html](design_system.html).

## 1. Objetivo

Ajudar a pessoa a **ficar dentro das verbas que ela mesma definiu** e a **entender para onde o dinheiro vai**, com gráficos.
A anotação automática é a planilha se preenchendo sozinha: cada notificação de banco/cartão vira um recado que a pessoa só confirma.

O app trata **só gastos**. Entradas de dinheiro (salário, Pix recebido) não têm lugar; esses recados são riscados.
O estorno não é crédito: é só o registro de que uma compra não conta (§6).

## 2. Do recado ao lançamento

1. **Anotação automática** guarda as notificações das **fontes** escolhidas como **recados**.
2. Recados não decididos ficam na **Pauta**. Para cada um, a pessoa:
   - **Lança** — vira lançamento (no recado de estorno: **Registrar estorno**, §6); ou
   - **Risca** — não é gasto (mensagem, propaganda, entrada).
3. Riscar mostra **Desfazer** na hora; depois, o recado pode voltar à Pauta pela lista **Riscados** (em Ajustes).
4. **Lançar exige exatamente uma verba e pelo menos um marcador.**
5. **Sugestão:** se o estabelecimento já teve lançamento, verba e marcadores do último lançamento dele vêm pré-selecionados (marcadores tracejados até a pessoa confirmar). Sem histórico, nada vem marcado.
6. Valor, estabelecimento e data vêm pré-preenchidos do texto do recado; a pessoa pode **emendar**.
7. **Lançamento manual:** a pessoa cria um lançamento sem recado (dinheiro, Pix de outro celular), com os mesmos campos e as mesmas exigências.
8. Apagar um lançamento devolve o recado dele à Pauta (o manual simplesmente some).
9. Apagar um marcador apaga os lançamentos que ficam sem nenhum marcador; os recados deles voltam à Pauta.
10. **Apelido** do estabelecimento substitui o texto do banco em todo lugar.
11. Recados que chegaram desde a última vez que a Pauta foi aberta aparecem como **novos**.

## 3. Verba e cota

- **Verba**: nome + **cota mensal**. Cada lançamento pertence a exatamente uma verba.
- **Mês da verba**: começa no **dia de início do mês**, escolhido em Ajustes, o mesmo para todas as verbas (padrão: dia 1).
  Vai do dia D até a véspera do dia D do mês seguinte. Se D não existe no mês (29–31), usa o último dia.
- **Mudar a cota** vale do **mês atual em diante**. Cada mês guarda a cota que valia nele; a Leitura compara cada mês com a sua.
- **Virada**: a cota recomeça inteira todo mês. Sobra e excesso não passam para o mês seguinte; ficam só no histórico.
- **Não há total geral**: cada verba vale por si. Não existe limite do mês acima das verbas.
- **Verba criada no meio do mês** tem a cota cheia no mês inteiro (o ritmo ideal de hoje já conta as semanas passadas).
- **Apagar uma verba** apaga os lançamentos dela; os recados voltam à Pauta.
- **Margem** = cota − gasto do mês.
- **Gasto do mês** = soma das parcelas (§6; à vista = 1 parcela) dos lançamentos da verba que caem no mês, sem os estornados.

## 4. Passo semanal

Uma **visualização** de quanto cabe por semana. Não é limite e não muda com o que foi gasto.

- **Semanas**: semanas de calendário, com **dia de início da semana** escolhido em Ajustes (padrão: segunda), cortadas no mês da verba.
  A primeira e a última semana do mês podem ser parciais.
- **Passo padrão** de uma semana = cota × dias da semana no mês ÷ dias do mês.
  Ex.: cota R$ 600, mês de 30 dias → semana cheia R$ 140,00; semana de 2 dias R$ 40,00.
- **Editar**: a pessoa muda o passo de uma semana do mês (ex.: gastar menos no começo).
  As semanas **não editadas** se ajustam, proporcionalmente aos dias, para a soma dos passos continuar igual à cota.
- **Uma semana fica sempre automática**: a última semana não editada não pode ser editada; ela absorve a diferença.
- **Limite da edição**: um passo aceita no máximo cota − outras semanas editadas; semana automática nunca fica abaixo de R$ 0,00. A mensagem diz o máximo possível.
- **Centavos**: passos automáticos são arredondados para baixo; a última semana não editada recebe a diferença.
  Ex.: R$ 100,00 em 3 semanas iguais → 33,33 · 33,33 · 33,34.
- Edições valem para aquele mês daquela verba. Mudar a cota redistribui só as semanas não editadas.
- **Mudar o dia de início da semana ou do mês** apaga as edições de passo do mês atual em diante; o app avisa antes. Meses passados são recalculados com o dia novo.

## 5. Ritmo

- **Ritmo ideal** (hoje) = passos das semanas já fechadas do mês + passo da semana atual × dias corridos dela (com hoje) ÷ dias dela.
  Segue o plano editado: se a semana 1 foi baixada, o ritmo ideal também começa mais baixo.
- **Estados** da verba no mês:

| Estado | Quando |
|---|---|
| **No ritmo** | gasto ≤ ritmo ideal |
| **Acima do ritmo** | gasto > ritmo ideal e gasto ≤ cota |
| **Fora da margem** | gasto > cota (o excesso é dito em reais: "R$ 12,00 além") |

- **Sem tolerância**: um centavo acima do ritmo ideal já é Acima do ritmo.
- **No passo atual** (projeção) = gasto do mês × (soma dos passos do mês ÷ ritmo ideal de hoje). Segue o plano: quem está exatamente no ritmo projeta a cota.
  Sem projeção quando o ritmo ideal de hoje é R$ 0,00.

## 6. Estorno e parcelas

- **Estorno** não é lançamento; é só um registro: a compra estornada **não é somada** na verba nem na Leitura, no mês em que foi feita (o mês passado ganha margem retroativa se o estorno chega depois).
  Não pede verba nem marcador.
- **Registrar um estorno** = escolher a compra estornada:
  - um **lançamento** — ele deixa de contar; ou
  - um **recado ainda não lançado** — os dois recados (compra e estorno) saem da Pauta e nada conta.
- **Desfazer estorno**: snackbar com **Desfazer** logo após registrar; depois, a ação **Desfazer estorno** na compra estornada (em Lançamentos).
  A compra volta a contar e o recado de estorno volta à Pauta (o recado da compra não lançada também volta).
- **Parcelas**: ao lançar, a pessoa informa o **valor total** e o número de parcelas; a tela mostra a prévia ("10 × R$ 120,00").
  A parcela k conta na verba no k-ésimo mês a partir do mês da compra; a última parcela absorve os centavos.
  Ex.: R$ 1.200 em 10× → R$ 120 por mês, em 10 meses.
- **Estorno de compra parcelada**: tira **todas** as parcelas, inclusive as de meses passados.

## 7. Telas

Cinco abas: **Verbas** (início) · **Pauta** · **Lançamentos** · **Leitura** · **Ajustes**.

### 7.1 Verbas
- Lista das verbas do mês atual: nome, régua (gasto, marca do ritmo ideal, cota), margem, estado.
- **Ordenar**: seletor no topo — **risco** (padrão; mesma regra do widget, §8.1) · **manual** (arrastar) · **A–Z**. A escolha é lembrada.
- Criar verba: nome + cota mensal.
- **Detalhe da verba**: seletor de mês; régua; passos semanais (editáveis, §4); lançamentos do mês; editar nome e cota; apagar.
- Vazio: explica o que é uma verba e como criar a primeira.

### 7.2 Pauta
- Recados esperando decisão, mais recentes no topo: fonte, valor e estabelecimento lidos, hora.
- Ações: Lançar (abre a tela de lançar; no recado de estorno, **Registrar estorno**) e Riscar (com Desfazer). Sem gestos de deslizar.
- Recados **novos** (§2.11) ficam destacados.
- **Parece propaganda**: recado do qual não se leu valor nem estabelecimento ganha esse selo, e o botão vira "Lançar mesmo assim".
- Aviso de **anotação pausada** quando o acesso às notificações está desligado.
- Vazio: "Pauta limpa."

### 7.3 Lançar / editar lançamento
- Valor total, estabelecimento (com apelido), data e hora, verba (obrigatória), marcadores (≥1), número de parcelas.
- Mostra o texto original do recado e marca o que foi emendado.
- **Prévia**: mostra o efeito na verba antes de confirmar ("Mercado: margem R$ 300,00 → R$ 254,00").
- **Recado de estorno**: em vez dos campos, a pessoa escolhe a compra estornada (lançamento ou recado, §6); o app sugere a mais provável. O botão é **Registrar estorno**.

### 7.4 Lançamentos
- Todos os lançamentos, mais recentes no topo: estabelecimento, valor, verba, marcadores, data; compras estornadas riscadas como "estornada" (o estorno não tem linha própria) e parceladas marcadas.
- Compra estornada: ação **Desfazer estorno** (§6).
- Tocar abre a edição (7.3). Botão de **lançamento manual**.

### 7.5 Leitura
- **Verbas por mês**: gasto × cota mês a mês, por verba (cada mês com a sua cota).
- **Ritmo do mês**: gasto acumulado × plano dos passos, por verba, com a projeção "no passo atual".
- **Marcadores e estabelecimentos**: ranking **por valor** no período escolhido, com a contagem ao lado (estabelecimentos pelo apelido).
  Um lançamento com vários marcadores conta inteiro em cada um; a tela avisa que a soma dos marcadores pode passar do total.
- **Calendário de gastos**: mapa de calor por dia.

### 7.6 Ajustes
- Fontes (quais apps são anotados).
- Riscados (trazer recados de volta à Pauta).
- Marcadores (renomear, juntar, apagar — §2.9).
- Dia de início do mês e dia de início da semana (§4: apaga edições de passo do mês atual em diante).
- Tema: sistema (padrão) · claro · escuro. Vale também para os widgets.
- Ordem do widget de Verbas: risco (padrão) · manual · A–Z.
- Backup (exportar / importar).
- Importar do cobudget (uma vez): traz recados, fontes, marcadores e apelidos de um backup do app antigo; recados repetidos não entram. Lançamentos antigos não têm verba, então os recados deles voltam à Pauta.

## 8. Widgets

Os dois são redimensionáveis de 2×2 a 4×4; o conteúdo se adapta. Tema segue o ajuste do app.
Tocar em qualquer ponto abre a aba correspondente (Verbas ou Pauta).

### 8.1 Verbas
- Mostra **todas** as verbas, cabendo quantas o tamanho permitir (2×2 = 1, 4×2 = 3, 4×4 = 6), na ordem escolhida em Ajustes. Padrão: das mais em risco para as menos:
  1. Fora da margem — maior excesso em R$ primeiro;
  2. Acima do ritmo — maior diferença para o ritmo ideal primeiro;
  3. No ritmo — menor margem em % da cota primeiro.
- Cada verba: nome, margem, régua com a marca do ritmo.

### 8.2 Pauta
- Contagem de recados e os mais recentes (valor, estabelecimento): 2×2 = só a contagem, 4×2 = + 2 recados, 4×4 = + 5.
- Sem ações no widget.

## 9. Fora do escopo

- **Notificações do próprio app**: nenhuma; os widgets cumprem esse papel.
- **Onboarding**: não há; o app abre em Verbas, e os estados vazios e o aviso de anotação pausada guiam a pessoa.
- **genda**: fica fora deste spec.
- **Entradas de dinheiro**: §1.

## 10. Em aberto

| # | Questão |
|---|---|
| — | Nenhuma no momento. |
