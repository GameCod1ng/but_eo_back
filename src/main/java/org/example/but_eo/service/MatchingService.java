package org.example.but_eo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.but_eo.dto.*;
import org.example.but_eo.entity.*;
import org.example.but_eo.repository.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class MatchingService {

    private final TeamRepository teamRepository;
    private final UsersRepository usersRepository;
    private final StadiumRepository stadiumRepository;
    private final MatchingRepository matchingRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final ChallengerListRepository challengerListRepository;

    @Transactional
    public void createMatch(MatchCreateRequest request, String userId) {
        log.info("UserHashId : " + userId);
        TeamMember leader = teamMemberRepository.findByUser_UserHashIdAndType(userId, TeamMember.Type.LEADER)
                .orElseThrow(() -> new RuntimeException("리더 팀이 없습니다."));

        Team team = leader.getTeam();

        Matching matching = new Matching();
        matching.setMatchId(UUID.randomUUID().toString());
        matching.setTeam(team);
        matching.setRegion(request.getRegion());
        matching.setEtc(request.getEtc());
        matching.setState(Matching.State.WAITING);

        // 날짜 + 시간 파싱
        LocalDate date;
        LocalTime time;
        LocalDateTime matchDate;
        try {
            date = LocalDate.parse(request.getMatchDay().trim());
            time = LocalTime.parse(request.getMatchTime().trim());
            System.out.println("matchDay raw: '" + request.getMatchDay() + "'");
            System.out.println("matchTime raw: '" + request.getMatchTime() + "'");
            matchDate = LocalDateTime.of(date, time);
            matching.setMatchDate(matchDate);
        } catch (Exception e) {
            System.err.println("날짜 또는 시간 파싱 오류 발생!");
            System.err.println("오류 메시지: " + e.getMessage()); // 이 부분이 핵심
            e.printStackTrace(); // 스택 트레이스도 출력하여 원인 파악
            throw new RuntimeException("날짜 또는 시간 형식이 잘못되었습니다.");
        }
    
        // 같은 팀이 같은 시간에 등록한 매치가 있는지 확인
        boolean exists = matchingRepository.existsByTeam_TeamIdAndMatchDate(team.getTeamId(), matchDate);
        if (exists) {
            throw new RuntimeException("해당 시간에 이미 등록된 매치가 존재합니다.");
        }

        // 대여 여부 파싱
        try {
            matching.setLoan(Boolean.parseBoolean(request.getLoan()));
        } catch (Exception e) {
            throw new RuntimeException("대여 여부 형식이 잘못되었습니다.");
        }

        // 종목 파싱
        try {
            matching.setMatchType(Matching.Match_Type.valueOf(request.getMatchType().toUpperCase()));
        } catch (Exception e) {
            throw new RuntimeException("매치 타입이 잘못되었습니다.");
        }

        matchingRepository.save(matching);
    }


    public Page<MatchingListResponse> getMatchings(Matching.Match_Type matchType, String region, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("matchDate").descending());

        Page<Matching> matchingPage;

        if (matchType != null && region != null) {
            matchingPage = matchingRepository.findByMatchTypeAndStadium_StadiumRegionAndState(
                    matchType, region, Matching.State.WAITING, pageable);
        } else if (matchType != null) {
            matchingPage = matchingRepository.findByMatchTypeAndState(
                    matchType, Matching.State.WAITING, pageable);
        } else if (region != null) {
            matchingPage = matchingRepository.findByRegionAndState(
                    region, Matching.State.WAITING, pageable);
        } else {
            matchingPage = matchingRepository.findByState(Matching.State.WAITING, pageable);
        }

        return matchingPage.map(m -> new MatchingListResponse(
                m.getMatchId(),
                m.getTeam().getTeamName(),
                m.getTeam().getRegion(),
                m.getStadium() != null ? m.getStadium().getStadiumName() : "미정",
                m.getMatchDate(),
                m.getMatchType(),
                m.getLoan()
        ));
    }

    public MatchingDetailResponse getMatchDetail(String matchId) {
        Matching matching = matchingRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("매치가 존재하지 않습니다."));

        return new MatchingDetailResponse(
                matching.getMatchId(),
                matching.getTeam().getTeamName(),
                matching.getTeam().getRegion(),
                matching.getStadium() != null ? matching.getStadium().getStadiumName() : "미정",
                matching.getStadium() != null ? matching.getStadium().getStadiumRegion() : "미정",
                matching.getMatchDate(),
                matching.getLoan(),
                matching.getMatchType(),
                matching.getEtc(),
                matching.getChallengerTeam() != null ? matching.getChallengerTeam().getTeamName() : null,
                matching.getWinnerScore(),
                matching.getLoserScore(),
                matching.getState()
        );
    }

    @Transactional
    public void applyChallenge(String matchId, String userId) {
        Matching matching = matchingRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("매치 없음"));

        if (matching.getState() != Matching.State.WAITING) {
            throw new RuntimeException("이미 도전이 수락된 매치입니다.");
        }

        // 현재 유저가 속한 팀 찾기 (도전자)
        TeamMember member = teamMemberRepository.findByUser_UserHashId(userId)
                .orElseThrow(() -> new RuntimeException("팀 멤버가 아님"));
        Team challengerTeam = member.getTeam();

        // 자기가 만든 매치에 도전 x
        if (matching.getTeam().getTeamId().equals(challengerTeam.getTeamId())) {
            throw new RuntimeException("자기 팀 매치에는 도전할 수 없습니다.");
        }

        // 중복 도전 x
        ChallengerKey key = new ChallengerKey(matchId, challengerTeam.getTeamId());
        if (challengerListRepository.existsById(key)) {
            throw new RuntimeException("이미 도전 신청한 매치입니다.");
        }

        // 도전 신청 저장
        ChallengerList challenger = new ChallengerList();
        challenger.setChallengerKey(key);
        challenger.setMatching(matching);
        challenger.setTeam(challengerTeam);
        challengerListRepository.save(challenger);
    }

    public List<ChallengerTeamResponse> getChallengerTeams(String matchId, String userId) {
        Matching match = matchingRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("매치가 존재하지 않습니다."));

        Team hostTeam = match.getTeam();

        // 요청자가 리더인지 확인
        boolean isLeader = hostTeam.getTeamMemberList().stream()
                .anyMatch(m -> m.getUser().getUserHashId().equals(userId)
                        && m.getType() == TeamMember.Type.LEADER);
        if (!isLeader) {
            throw new RuntimeException("리더만 도전 목록을 조회할 수 있습니다.");
        }

        // 도전자 목록 가져오기
        List<ChallengerList> challengers = challengerListRepository.findByMatching_MatchId(matchId);

        return challengers.stream()
                .map(c -> {
                    Team team = c.getTeam();
                    return new ChallengerTeamResponse(
                            team.getTeamId(),
                            team.getTeamName(),
                            team.getRegion(),
                            team.getRating()
                    );
                })
                .toList();
    }

    @Transactional
    public void acceptChallenge(String matchId, String challengerTeamId, String userId) {
        Matching matching = matchingRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("매치 없음"));

        if (matching.getState() != Matching.State.WAITING)
            throw new RuntimeException("이미 수락된 매치입니다.");

        Team hostTeam = matching.getTeam();
        boolean isLeader = hostTeam.getTeamMemberList().stream()
                .anyMatch(m -> m.getUser().getUserHashId().equals(userId)
                        && m.getType() == TeamMember.Type.LEADER);
        if (!isLeader)
            throw new RuntimeException("리더만 수락할 수 있습니다.");

        Team challenger = teamRepository.findById(challengerTeamId)
                .orElseThrow(() -> new RuntimeException("도전 팀 없음"));

        // 도전 신청 존재 여부 확인
        ChallengerKey key = new ChallengerKey(matchId, challengerTeamId);
        if (!challengerListRepository.existsById(key)) {
            throw new RuntimeException("도전 신청이 없습니다.");
        }

        // 수락 처리
        matching.setChallengerTeam(challenger);
        matching.setState(Matching.State.SUCCESS);
        matchingRepository.save(matching);

        // 나머지 도전 신청들 제거
        challengerListRepository.deleteAllByMatching_MatchId(matchId);
    }

    @Transactional
    public void declineChallenge(String matchId, String challengerTeamId, String userId) {
        Matching matching = matchingRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("매치 없음"));

        Team hostTeam = matching.getTeam();
        boolean isLeader = hostTeam.getTeamMemberList().stream()
                .anyMatch(m -> m.getUser().getUserHashId().equals(userId)
                        && m.getType() == TeamMember.Type.LEADER);
        if (!isLeader)
            throw new RuntimeException("리더만 거절할 수 있습니다.");

        ChallengerKey key = new ChallengerKey(matchId, challengerTeamId);
        if (!challengerListRepository.existsById(key)) {
            throw new RuntimeException("도전 신청이 없습니다.");
        }

        challengerListRepository.deleteById(key);
    }

    @Transactional
    public void cancelMatch(String matchId, String userId) {
        Matching matching = matchingRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("매치가 존재하지 않습니다."));

        // 상태 체크: WAITING만 가능
        if (matching.getState() != Matching.State.WAITING) {
            throw new RuntimeException("매치가 대기 상태일 때만 취소할 수 있습니다.");
        }

        //리더인지 체크
        Team team = matching.getTeam();
        boolean isLeader = team.getTeamMemberList().stream()
                .anyMatch(m -> m.getUser().getUserHashId().equals(userId)
                        && m.getType() == TeamMember.Type.LEADER);
        if (!isLeader) {
            throw new RuntimeException("리더만 매치를 취소할 수 있습니다.");
        }

        matching.setState(Matching.State.CANCEL);
        matchingRepository.save(matching);
    }

    //매치 결과 등록
    @Transactional
    public void registerMatchResult(String matchId, MatchResultRequest request, String userId) {
        Matching matching = matchingRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("매치가 존재하지 않습니다."));

        // 상태가 SUCCESS인지 확인
        if (matching.getState() != Matching.State.SUCCESS) {
            throw new RuntimeException("도전 수락된 매치만 결과 등록이 가능합니다.");
        }
        
        //리더인지 체크
        Team hostTeam = matching.getTeam();
        boolean isLeader = hostTeam.getTeamMemberList().stream()
                .anyMatch(m -> m.getUser().getUserHashId().equals(userId)
                        && m.getType() == TeamMember.Type.LEADER);
        if (!isLeader) {
            throw new RuntimeException("리더만 결과를 등록할 수 있습니다.");
        }
    
        //스코어 가져오기
        int winnerScore = request.getWinnerScore();
        int loserScore = request.getLoserScore();

        Team team1 = matching.getTeam();              // 주최팀
        Team team2 = matching.getChallengerTeam();    // 도전자팀

        if (team2 == null) {
            throw new RuntimeException("도전자 팀 정보가 없습니다.");
        }

        // 승리 팀 자동 판별
        Team winnerTeam = null;
        Team loserTeam = null;

        if (winnerScore > loserScore) {
            winnerTeam = team1;
            loserTeam = team2;
        } else if (winnerScore < loserScore) {
            winnerTeam = team2;
            loserTeam = team1;
        }

        matching.setWinnerScore(winnerScore);
        matching.setLoserScore(loserScore);
        matching.setWinnerTeam(winnerTeam);
        matching.setLoserTeam(loserTeam);
        matching.setState(Matching.State.COMPLETE);

        // 레이팅 반영
        if (winnerScore > loserScore) {
            // 승자 처리
            winnerTeam.setRating(winnerTeam.getRating() + 3);
            winnerTeam.setMatchCount(winnerTeam.getMatchCount() + 1);
            winnerTeam.setWinCount(winnerTeam.getWinCount() + 1);

            // 패자 처리
            loserTeam.setMatchCount(loserTeam.getMatchCount() + 1);
            loserTeam.setLoseCount(loserTeam.getLoseCount() + 1);
        } else if (winnerScore == loserScore) {
            int bonus = (winnerScore == 0) ? 1 : 2;         //무승부면 2점 0:0이면 1점
            team1.setRating(team1.getRating() + bonus);
            team2.setRating(team2.getRating() + bonus);
            
            //매치 카운트 증가
            team1.setMatchCount(team1.getMatchCount() + 1); 
            team2.setMatchCount(team2.getMatchCount() + 1);
            
            //무승부 카운트 증가
            team1.setDrawCount(team1.getDrawCount() + 1);
            team2.setDrawCount(team2.getDrawCount() + 1);
        }

        matchingRepository.save(matching);
    }

}

