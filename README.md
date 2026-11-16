<p align="center">
  <img src="icons/128.png" width="96" alt="Drafty icon">
</p>

<h1 align="center">Drafty</h1>

<p align="center">
  AI replies, summaries and page tools for chats, emails and comments.<br>
  Uses your own Claude or Gemini account. No API keys.
</p>

<p align="center">
  <a href="https://github.com/Kshitiz726/Drafty/releases/latest/download/Drafty.apk">
    <img src="https://img.shields.io/badge/Download-Android%20APK-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Download Android APK">
  </a>
  &nbsp;
  <a href="https://github.com/Kshitiz726/Drafty/releases/latest">
    <img src="https://img.shields.io/github/v/release/Kshitiz726/Drafty?style=for-the-badge&label=Latest" alt="Latest release">
  </a>
</p>

## Download

- **Android:** tap the **Download Android APK** button above on your phone, open the file and allow installs from your browser if asked. Android 8 or newer.
- **Browser extension:** download this repository (**Code > Download ZIP**), unzip it and follow the steps below.

## Browser extension (Chrome, Brave, Edge, Arc)

**Install:** open `chrome://extensions` (or `brave://extensions`), turn on Developer mode, click **Load unpacked** and pick this folder. After an update, click **Reload** and refresh your open tabs.

**Pick your AI** in the popup: Claude or Gemini. Sign in once in a normal tab. Drafty never opens tabs and, with Keep chat history clean on, deletes its chats from your account after each reply.

**Reply anywhere:** click into a message, email or comment box, then click **Reply** or press **Alt+R**.

| Tab | What it does |
|---|---|
| Reply | Drafts in your style and language. Refine with chips or an instruction. Select part of the draft to change only that part. |
| Catch up | What was asked of you, what is done, what is open, next step |
| Analyze | Reads the whole loaded conversation: tone, intent, key points, how to respond |
| Summarize | Thread or post summary with reactions |
| Translate | Translates the recent messages into your chosen language |
| Ideas | Three different replies to pick from |

New messages while the panel is open show a **Redraft with latest** link. Turn on **Insert replies automatically** in the popup to skip the Insert step.

**Page assistant (Alt+A):** TL;DR, Summarize comments, Opinions and sentiment, Best comments, Write a comment, Explain simply, **Find comments** by meaning, and **Ask** anything. Citations like `C12` jump to the comment.

## Android app

Install the APK from the [latest release](https://github.com/Kshitiz726/Drafty/releases/latest) and open it. A short guided setup connects your AI, turns on the Drafty button and removes battery limits. It can be reopened from Home.

One floating Drafty button holds everything. Drag it anywhere and it snaps to the edge.
- **Tap in a chat:** the reply is written into the box, even if you haven't opened it. Tap again for a fresh one. In a comment box, it writes a comment using the post and other comments. With no chat open, it says so and does nothing.
- **Photos:** when someone sends a photo, the reply reacts naturally and asks about it.
- **Long-press:** the menu opens next to the button and can be dragged anywhere. All options: Regenerate, shorter, warmer, formal, translate, catch up, analyze, and on posts TL;DR, summarize comments, opinions, best comments, find and ask.

**Settings**
- **Draft automatically:** a reply appears as soon as you open a chat's reply box.
- **Send automatically:** after a draft is written in an open chat, Drafty sends it.
- **Reply when phone is locked:** answers new 1:1 messages from their notification while your screen is off or locked, using the whole conversation. Choose **As me**, **I'm away** or **Fixed text**, add instructions and pick apps. Group chats and comments are never answered.
- **Memory half-life (3 to 100 messages):** how far back Drafty looks in a chat.
- **Comments to read:** how many comments Drafty scrolls through for comment replies and tools (5 to 100).
- **Appearance:** System, Light or Dark.

## Context selection

Each message gets a weight `w = 2^(-age / h) · 0.35^(gaps over 3h)`. Drafty sends the shortest recent stretch that holds 95% of the total weight, always includes unanswered messages, and fits the token budget. Comments are ranked by thread distance and upvotes, then packed into the budget.

## Notes
- Drafty uses each service's own web app with your session. These are not official APIs and may change. Usage counts against your plan.
- Replies are written to sound human: no em dashes, no assistant filler, and no "[yes / no]" placeholders.
- Drafty only reads content that is on screen or loaded on the page. Chat memory stays on your phone.

## Build from source

```bash
cd android
./gradlew assembleRelease
```

Requires JDK 17 and the Android SDK (API 35).
