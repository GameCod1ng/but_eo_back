package org.example.but_eo.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.but_eo.dto.ChatMember;
import org.example.but_eo.dto.ChatMessage;
import org.example.but_eo.dto.ChattingDTO;
import org.example.but_eo.dto.CreateChatRoomRequest;
import org.example.but_eo.entity.Chatting;
import org.example.but_eo.service.ChattingMessageService;
import org.example.but_eo.service.ChattingService;
import org.example.but_eo.service.RedisChatService;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final ChattingService chattingService;
    private final SimpMessagingTemplate messagingTemplate;
    private final RedisChatService redisChatService;
    private final ChattingMessageService chattingMessageService;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @MessageMapping("chat/enter") // 현재 세팅의 경우 클라이언트에서 보낼 때 /app/chat/message -> 클라이언트가 채팅을 보낼때 입장이나 등등
    public void enter(@Payload ChatMessage message) {
        message.setMessageId(UUID.randomUUID().toString());
        message.setCreatedAt(LocalDateTime.now().toString());

        if (message.getType() == ChatMessage.MessageType.ENTER) { // 메세지 타입이 입장일 경우
            message.setMessage(message.getSender() + "님이 입장하셨습니다"); // 개발 단계에서만 보이게끔

            // 🔽 과거 메시지 조회
//            List<ChatMessage> history = redisChatService.getRecentMessages(message.getRoomId());

            List<ChatMessage> history = new ArrayList<>();

            history.addAll(chattingMessageService.findByMessages(message.getMessageId()));
            history.addAll(redisChatService.getRecentMessages(message.getChat_id()));

            //convertAndSendToUser
            messagingTemplate.convertAndSendToUser(
                    message.getSender(), // Flutter에서 sender를 유저 고유값으로 설정
                    "/all/chatroom/" + message.getChat_id(),    // 클라이언트가 구독할 주소
                    history
            );

        } else if (message.getType() == ChatMessage.MessageType.EXIT) { // 메세지 타입이 퇴장일 경우
            message.setMessage(message.getSender() + "님이 퇴장하셨습니다"); // 개발 단계에서만 보이게끔
        }

        redisChatService.saveMessageToRedis(message.getChat_id(), message);
//        System.out.println("전송 메시지 : " + message);

        messagingTemplate.convertAndSend("/all/chat/" + message.getChat_id(), message); //클라이언트가 메세지를 받을때
    }

    @MessageMapping("/chat/message")
    public void message(@Payload ChatMessage message, Principal principal) {
        if(principal!=null){
            String userId = principal.getName();
            System.out.println(userId);

            message.setSender(userId);
            message.setMessageId(UUID.randomUUID().toString());
            message.setNickName(chattingService.getNickName(userId));
            message.setCreatedAt(LocalDateTime.now().toString());

            redisChatService.saveMessageToRedis(message.getChat_id(), message);
            messagingTemplate.convertAndSend("/all/chat/" + message.getChat_id(), message);
            System.out.println("메세지 전송된 채팅방 아이디 : " + message.getChat_id());
            System.out.println("메세지 내용 : " + message.getMessage());
        }
        else{
            System.out.println("WebSocket 연결에 인증된 사용자 없음");
        }
    }

    @GetMapping("/load/messages/{roomId}")
    @ResponseBody
    public List<ChatMessage> getMessages(@PathVariable String roomId) {
        String key = "chatroom:" + roomId;

        //Flutter에서는 메세지를 Map으로 파싱하려고 함 -> 역직렬화 필요
        List<String> rawMessages = redisTemplate.opsForList().range(key, 0, -1);
//        ObjectMapper mapper = new ObjectMapper();
        List<ChatMessage> messages = new ArrayList<>();

        for(String json : rawMessages){
            try {
                ChatMessage message = objectMapper.readValue(json, ChatMessage.class); //Jackson
                messages.add(message);
            }catch (JsonProcessingException e) {
                e.printStackTrace();
            }
        }
        System.out.println(messages);
        return messages;
    }

    @GetMapping("/load/members/{roomId}")
    @ResponseBody
    public List<ChatMember> getMembers(@PathVariable String roomId) {
        List<ChatMember> members = chattingService.getChatMembers(roomId);
        if(members!=null) {
            System.out.println("채팅방 소속 인원 : " + members);
        }
        return chattingService.getChatMembers(roomId);
    }


    @PostMapping("/chatrooms")
    public ResponseEntity<Chatting> createChatRoom(@RequestBody CreateChatRoomRequest request, Authentication authentication) {
        String userId = (String) authentication.getPrincipal();
        request.getUserHashId().add(userId);
        Chatting chatRoom = chattingService.createChatRoom(request.getUserHashId(), request.getChatRoomName());
        //TODO : 채팅방 아이디도 전송
        return ResponseEntity.ok(chatRoom);
    }

    //유저 아이디 -> 채팅방 조회
    @GetMapping("/searchChatRooms")
    public ResponseEntity<?> searchChatRoom(Authentication authentication) {
        String userId = (String) authentication.getPrincipal();

        if (userId == null) {
            log.warn("인증된 사용자가 없습니다.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인이 필요합니다.");
        }

        List<ChattingDTO> rooms = chattingService.searchChatRooms(userId);
        System.out.println("현재 접속된 유저 아이디 : " + userId);
        if (rooms.isEmpty()) {
            System.out.println("현재 접속된 유저의 채팅방이 없습니다");
        } else {
            for (ChattingDTO room : rooms) {
                System.out.println("현재 접속된 유저 채팅방 리스트 : " + room.getRoomName());
            }
        }
        return ResponseEntity.ok(rooms);
    }

    @PostMapping("/exit/ChatRoom/{roomId}")
    public ResponseEntity<Void> exitChatRoom(@PathVariable String roomId, Authentication authentication) {
        String userId = (String) authentication.getPrincipal();
        if(userId!=null){
            System.out.println("채팅방 : " + roomId + "나간 인원 : " + userId);
        }
        chattingService.exitChatRoom(userId, roomId);
        return ResponseEntity.noContent().build();
    }
}
