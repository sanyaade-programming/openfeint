//  Copyright 2009-2010 Aurora Feint, Inc.
// 
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//  
//  	http://www.apache.org/licenses/LICENSE-2.0
//  	
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.

#import "OFImageLoader.h"
#import "OFDeviceContact.h"
#import "OFImageView.h"
#import "OFSelectableContactCell.h"
#import "OFDependencies.h"

@implementation OFSelectableContactCell

@synthesize checkbox, checked;

- (void)setChecked:(BOOL)_checked
{
	checked = _checked;
	if (checked)
	{
		[checkbox setImage:[OFImageLoader loadImage:@"OFCheckBoxSelected.png"]];
	}
	else
	{
		[checkbox setImage:[OFImageLoader loadImage:@"OFCheckBox.png"]];
	}	
}



- (void)dealloc
{
	OFSafeRelease(checkbox);
	[super dealloc];
}

@end
