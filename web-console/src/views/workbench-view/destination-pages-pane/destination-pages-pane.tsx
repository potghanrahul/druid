/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import {
  AnchorButton,
  Button,
  ControlGroup,
  InputGroup,
  Intent,
  Label,
  Menu,
  MenuItem,
  Popover,
  Position,
} from '@blueprintjs/core';
import { IconNames } from '@blueprintjs/icons';
import React, { useState } from 'react';
import ReactTable from 'react-table';

import type { Execution } from '../../../druid-models';
import { SMALL_TABLE_PAGE_SIZE } from '../../../react-table';
import { Api, UrlBaser } from '../../../singletons';
import { clamp, formatBytes, formatInteger, pluralIfNeeded, tickIcon } from '../../../utils';

import './destination-pages-pane.scss';

type ResultFormat = 'object' | 'array' | 'objectLines' | 'arrayLines' | 'csv';

const RESULT_FORMATS: ResultFormat[] = ['objectLines', 'object', 'arrayLines', 'array', 'csv'];

function resultFormatToExtension(resultFormat: ResultFormat): string {
  switch (resultFormat) {
    case 'object':
    case 'array':
      return 'json';

    case 'objectLines':
    case 'arrayLines':
      return 'jsonl';

    case 'csv':
      return 'csv';
  }
}

const RESULT_FORMAT_DESCRIPTION: Record<ResultFormat, string> = {
  object: 'Array of objects',
  array: 'Array of arrays',
  objectLines: 'JSON Lines',
  arrayLines: 'JSON Lines but every row is an array',
  csv: 'CSV',
};

interface DestinationPagesPaneProps {
  execution: Execution;
}

export const DestinationPagesPane = React.memo(function DestinationPagesPane(
  props: DestinationPagesPaneProps,
) {
  const { execution } = props;
  const [prefix, setPrefix] = useState(execution.id);
  const [desiredResultFormat, setDesiredResultFormat] = useState<ResultFormat>('objectLines');
  const desiredExtension = resultFormatToExtension(desiredResultFormat);

  const destination = execution.destination;
  const pages = execution.destinationPages;
  if (!pages) return null;
  const numPages = pages.length;

  const numTotalRows = destination?.numTotalRows;

  function getResultUrl(pageIndex: number) {
    return UrlBaser.base(
      `/druid/v2/sql/statements/${Api.encodePath(execution.id)}/results?${
        pageIndex < 0 ? '' : `page=${pageIndex}&`
      }resultFormat=${desiredResultFormat}&filename=${getPageFilename(pageIndex)}`,
    );
  }

  function getFilenamePageInfo(pageIndex: number) {
    if (pageIndex < 0) return '';
    const numPagesString = String(numPages);
    const pageNumberString = String(pageIndex + 1).padStart(numPagesString.length, '0');
    return `.page_${pageNumberString}_of_${numPagesString}`;
  }

  function getPageFilename(pageIndex: number) {
    return `${prefix}${getFilenamePageInfo(pageIndex)}.${desiredExtension}`;
  }

  return (
    <div className="destination-pages-pane">
      <p>
        {`${
          typeof numTotalRows === 'number' ? pluralIfNeeded(numTotalRows, 'row') : 'Results'
        } have been written to ${pluralIfNeeded(numPages, 'page')}. `}
      </p>
      <p>
        <Label>Download as</Label>
        <ControlGroup className="download-as-controls">
          <InputGroup
            value={prefix}
            onChange={(e: any) => setPrefix(e.target.value.slice(0, 200))}
            placeholder="file_prefix"
            rightElement={<Button disabled text={`.${desiredExtension}`} />}
            fill
          />
          <Popover
            minimal
            position={Position.BOTTOM_LEFT}
            content={
              <Menu>
                {RESULT_FORMATS.map((resultFormat, i) => (
                  <MenuItem
                    key={i}
                    icon={tickIcon(desiredResultFormat === resultFormat)}
                    text={RESULT_FORMAT_DESCRIPTION[resultFormat]}
                    label={resultFormat}
                    onClick={() => setDesiredResultFormat(resultFormat)}
                  />
                ))}
              </Menu>
            }
          >
            <Button
              text={RESULT_FORMAT_DESCRIPTION[desiredResultFormat]}
              rightIcon={IconNames.CARET_DOWN}
            />
          </Popover>
        </ControlGroup>
        <AnchorButton
          intent={Intent.PRIMARY}
          icon={IconNames.DOWNLOAD}
          text="Download all data (concatenated)"
          href={getResultUrl(-1)}
          download
        />
      </p>
      <ReactTable
        data={pages}
        loading={false}
        sortable={false}
        defaultPageSize={clamp(numPages, 1, SMALL_TABLE_PAGE_SIZE)}
        showPagination={numPages > SMALL_TABLE_PAGE_SIZE}
        columns={[
          {
            Header: 'Page ID',
            id: 'id',
            accessor: 'id',
            className: 'padded',
            width: 100,
          },
          {
            Header: 'Number of rows',
            id: 'numRows',
            accessor: 'numRows',
            className: 'padded',
            width: 200,
            Cell: ({ value }) => formatInteger(value),
          },
          {
            Header: 'Size',
            id: 'sizeInBytes',
            accessor: 'sizeInBytes',
            className: 'padded',
            width: 200,
            Cell: ({ value }) => formatBytes(value),
          },
          {
            Header: '',
            id: 'download',
            accessor: 'id',
            width: 130,
            Cell: ({ value }) => (
              <AnchorButton
                className="download-button"
                icon={IconNames.DOWNLOAD}
                text="Download"
                minimal
                href={getResultUrl(value)}
                download
              />
            ),
          },
        ]}
      />
    </div>
  );
});
