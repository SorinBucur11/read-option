post-4.4 six-tool prompt

{"message": "Which player should I pick?"}

2026-07-12T04:00:44.125+03:00 DEBUG 12204 --- [read-option] [nio-8080-exec-8] app.readoption.agent.DraftAgentService   : iter 0 | hasToolCalls=true | tools=[getDraftState{}, getDraftBoard{}] | in=2430 out=72 | cumulative_in=2430 | 2076 ms
2026-07-12T04:00:44.125+03:00 DEBUG 12204 --- [read-option] [nio-8080-exec-8] app.readoption.agent.DraftAgentTools     : tool exec -> getDraftState [session 5]
2026-07-12T04:00:44.131+03:00 DEBUG 12204 --- [read-option] [nio-8080-exec-8] app.readoption.agent.DraftAgentTools     : tool exec <- getDraftState [session 5] pick 1 | 5 ms
2026-07-12T04:00:44.131+03:00 DEBUG 12204 --- [read-option] [nio-8080-exec-8] app.readoption.agent.DraftAgentTools     : tool exec -> getDraftBoard [session 5] position=null limit=null
2026-07-12T04:00:44.168+03:00 DEBUG 12204 --- [read-option] [nio-8080-exec-8] app.readoption.agent.DraftAgentTools     : tool exec <- getDraftBoard [session 5] 20 rows | 36 ms
2026-07-12T04:00:54.646+03:00 DEBUG 12204 --- [read-option] [nio-8080-exec-8] app.readoption.agent.DraftAgentService   : iter 1 | hasToolCalls=false | tools=[] | in=3532 out=386 | cumulative_in=5962 | 10478 ms
2026-07-12T04:00:54.647+03:00  INFO 12204 --- [read-option] [nio-8080-exec-8] app.readoption.agent.DraftAgentService   : Draft advice session 5: 1 tool iterations, 6420 total tokens, 12599 ms

2026-07-12T04:07:32.614+03:00 DEBUG 12204 --- [read-option] [nio-8080-exec-5] app.readoption.agent.DraftAgentService   : iter 0 | hasToolCalls=true | tools=[getDraftState{}, getDraftBoard{}] | in=2430 out=70 | cumulative_in=2430 | 2136 ms
2026-07-12T04:07:32.615+03:00 DEBUG 12204 --- [read-option] [nio-8080-exec-5] app.readoption.agent.DraftAgentTools     : tool exec -> getDraftState [session 6]
2026-07-12T04:07:32.622+03:00 DEBUG 12204 --- [read-option] [nio-8080-exec-5] app.readoption.agent.DraftAgentTools     : tool exec <- getDraftState [session 6] pick 1 | 6 ms
2026-07-12T04:07:32.623+03:00 DEBUG 12204 --- [read-option] [nio-8080-exec-5] app.readoption.agent.DraftAgentTools     : tool exec -> getDraftBoard [session 6] position=null limit=null
2026-07-12T04:07:32.649+03:00 DEBUG 12204 --- [read-option] [nio-8080-exec-5] app.readoption.agent.DraftAgentTools     : tool exec <- getDraftBoard [session 6] 20 rows | 25 ms
2026-07-12T04:07:42.960+03:00 DEBUG 12204 --- [read-option] [nio-8080-exec-5] app.readoption.agent.DraftAgentService   : iter 1 | hasToolCalls=false | tools=[] | in=3532 out=375 | cumulative_in=5962 | 10308 ms
2026-07-12T04:07:42.960+03:00  INFO 12204 --- [read-option] [nio-8080-exec-5] app.readoption.agent.DraftAgentService   : Draft advice session 6: 1 tool iterations, 6407 total tokens, 12482 ms