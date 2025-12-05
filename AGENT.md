How the interaction goes:

MESSAGES_FOLDER=app\src\main\res\raw

"User" messages to the AI, except the first which is really the system prompt, are saved to the AI conversation "decoarated" by the user's statistics, according to the template in MESSAGES_FOLDER\gemini_user_template.txt.
They get saved to the UI visible conversation undecoarated. 

AI replies are saved to AI history unaltered. 
They are saved to the UI visible conversation stripped of tool uses (lines starting with #)

The threshold starts at a configurable amount (by default 5 minutes) and replenishes by a number of minutes configurable in settings (by default, 1 minute every 10). When it hits the maximum amount again, the session resets; any new conversation will start form scratch (except what the AI chose to save).

0) app wakes up
1a) threshold of wasted time not exceeded. Wait observe_timer, then go to 0)
1b) threshold of wasted time exceeded. An initial AI history is created, composed of:
    - USER: Fixed system message from MESSAGES_FOLDER\gemini_system_prompt.txt. Note: this is the only message that should NOT be decorated like a regualr user message.
    - AI: Remembered messages (using the REMEMBER tool), passed to MESSAGES_FOLDER\ai_memory_template.txt
    - USER: Update on the user's status, using MESSAGES_FOLDER\user_info_template.txt. This will be decoareted with the user's app statistics, like all following USER messages.
This initial conversation will be added to the AI history and sent every time, but NOT added to the UI visible conversation. 
2) AI conversation history is sent to AI. 
3) AI responds. All tools are ran and removed from the AI message.
4) If there was any non-tool answer, it is displayed to the user.
5a) if there was an ALLOW tool use or no non-tool message, conversation resets. Go to 0)
5b) if no ALLOW and a non-tool message, the conversation continues. Wait response_timer.
6a) User did not respond within timeout. Add *no response* to the AI conversation, decorated as all user messages. Do NOT update the UI conversation. Continue.
6b) User answers. Save the response in both AI and UI conversations, AI one decorated. Continue. 
7) Go to 2) 

IMPORTANT NOTE: We target API 30 and above, do not add checks for lower versions.