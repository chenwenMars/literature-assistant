package com.literature.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.literature.common.exception.BusinessException;
import com.literature.common.result.ResultCode;
import com.literature.dto.LiteratureNoteRequest;
import com.literature.entity.LiteratureNote;
import com.literature.mapper.LiteratureMapper;
import com.literature.mapper.LiteratureNoteMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LiteratureNoteService {
    private final LiteratureMapper literatureMapper;
    private final LiteratureNoteMapper noteMapper;

    private void requireLiterature(Long literatureId) {
        if (literatureMapper.selectById(literatureId) == null) {
            throw new BusinessException(ResultCode.DATA_NOT_EXIST, "文献不存在或已删除");
        }
    }

    public List<LiteratureNote> list(Long literatureId) {
        requireLiterature(literatureId);
        return noteMapper.selectList(new LambdaQueryWrapper<LiteratureNote>()
                .eq(LiteratureNote::getLiteratureId, literatureId)
                .orderByDesc(LiteratureNote::getUpdateTime).orderByDesc(LiteratureNote::getId));
    }

    public LiteratureNote save(Long literatureId, Long noteId, LiteratureNoteRequest request) {
        requireLiterature(literatureId);
        LiteratureNote note = new LiteratureNote();
        note.setLiteratureId(literatureId);
        note.setTitle(request.getTitle());
        note.setCategory(request.getCategory().name());
        note.setSourceLocation(request.getSourceLocation());
        note.setExcerpt(request.getExcerpt());
        note.setReflection(request.getReflection());
        note.setUpdateTime(LocalDateTime.now());
        if (noteId == null) {
            note.setCreateTime(note.getUpdateTime());
            noteMapper.insert(note);
            return note;
        }
        note.setId(noteId);
        int updated = noteMapper.update(note, new LambdaUpdateWrapper<LiteratureNote>()
                .eq(LiteratureNote::getId, noteId).eq(LiteratureNote::getLiteratureId, literatureId));
        if (updated == 0) throw new BusinessException(ResultCode.DATA_NOT_EXIST, "当前文献的笔记不存在");
        return noteMapper.selectById(noteId);
    }

    public void delete(Long literatureId, Long noteId) {
        requireLiterature(literatureId);
        int deleted = noteMapper.delete(new LambdaQueryWrapper<LiteratureNote>()
                .eq(LiteratureNote::getId, noteId).eq(LiteratureNote::getLiteratureId, literatureId));
        if (deleted == 0) throw new BusinessException(ResultCode.DATA_NOT_EXIST, "当前文献的笔记不存在");
    }
}
